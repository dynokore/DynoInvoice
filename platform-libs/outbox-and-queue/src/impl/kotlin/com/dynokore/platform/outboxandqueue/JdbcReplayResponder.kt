package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Clock

/**
 * The producer side of the replay protocol (INV-REQ-1205 b4; rulings INV-Q-3599, INV-Q-3601, INV-Q-3625, INV-Q-3644,
 * INV-Q-3671, INV-Q-3676), run by every producing service over the request topics it reads
 * (`gst.<consumer>.replay-requested.v1`, under its consumer SELECT grant, INV-REQ-1205 b3):
 *
 * - each request is taken in log order, one at a time per consumer; a request addressed to another producer is
 *   acknowledged (inbox row and position) and changes nothing;
 * - a request addressed to this service (by name, or to 'every service' on a topic whose readers include 'every
 *   service') is answered on the pair's replay topic: for each [ReplayStateSource] whose type that topic carries, the
 *   states within the scope (TENANT, ENTITY or PERIOD; a full sync: everything) are written page by page, each page
 *   in its own transaction bound to its tenant, with fresh event ids and the states' own `docsequence`;
 * - last, in one transaction under the platform tenant, the request's inbox row, the position past it and the
 *   `ReplayCompleted` marker (replay id, events replayed) commit together, so the relay publishes the marker after
 *   every replayed event. A crash before that leaves the request unhandled: the next run reads it again and restarts the
 *   replay from the start under the same replay id; the consumer finds the re-sent states STALE.
 *
 * A request the service cannot answer (not a valid ReplayRequested, from another service than the topic's consumer, or
 * for a pair without a replay topic) is dead-lettered as a consumer's invalid record is, and the run moves on.
 *
 * Every statement is jOOQ on [dsl] (INV-T-5306): [scope] is the service's runtime login. Positions, dead letters
 * (through [OutboxSchema.DEAD_LETTER_RECORD]) and the per-topic transaction advisory lock (a jOOQ function call, held by
 * a transaction under the platform binding that spans the topic's run, INV-ADR-3968 item 9) each use a connection of
 * their own, outside any open transaction.
 */
class JdbcReplayResponder
    @JvmOverloads
    constructor(
        private val service: String,
        schema: String,
        private val catalogue: EventCatalogue,
        private val reader: EventLogReader,
        private val scope: TenantScope,
        private val dsl: DSLContext,
        private val writer: JdbcOutboxWriter,
        sources: Collection<ReplayStateSource>,
        private val sink: RebuildRequiredSink,
        private val clock: Clock = Clock.systemUTC(),
        private val pageSize: Int = DEFAULT_PAGE,
    ) {
        private val s = SqlIdentifier.require(schema, "schema")
        private val offsets: Table<*> = DSL.table(DSL.name(s, OutboxSchema.CONSUMER_OFFSET))
        private val inbox: Table<*> = DSL.table(DSL.name(s, OutboxSchema.INBOX))
        private val storedNext = DSL.field(DSL.name(s, OutboxSchema.CONSUMER_OFFSET, "next_offset"), SQLDataType.BIGINT)
        private val sourcesByType = sources.associateBy { it.eventType }
        private val envelopes = EnvelopeJson()
        private val json = ReplayJson()
        private val stopped = mutableSetOf<String>()

        init {
            require(sourcesByType.size == sources.size) { "one state source per event type" }
            require(pageSize in 1..MAX_PAGE) { "page size must be 1..$MAX_PAGE" }
        }

        /** Reads every request topic this service reads and answers what it finds there. Returns the replays answered. */
        @Synchronized
        fun runOnce(): List<ReplayAnswer> =
            catalogue.requestTopicsReadBy(service).flatMap { topic ->
                // the lock transaction writes nothing; its end releases the lock
                scope.bindWorkload(TenantId.PLATFORM) {
                    scope.transactions.inTransaction {
                        if (tryLock(topic.topic)) drain(topic) else emptyList()
                    }
                }
            }

        private fun drain(topic: ReplayRequestTopic): List<ReplayAnswer> {
            val next = position(topic.topic)
            val records =
                try {
                    reader.read(topic.topic, next - 1, BATCH)
                } catch (behind: RebuildRequired) {
                    if (stopped.add(topic.topic)) {
                        sink.raise(
                            RebuildRequiredIncident(
                                service,
                                topic.topic,
                                behind.nextOffset,
                                behind.watermark,
                                clock.instant(),
                            ),
                        )
                    }
                    return emptyList()
                }
            stopped.remove(topic.topic)
            return records.mapNotNull { answer(topic, it) }
        }

        private fun answer(
            topic: ReplayRequestTopic,
            record: LogRecord,
        ): ReplayAnswer? {
            val envelope: Envelope
            val request: ReplayRequest
            try {
                envelope = envelopes.read(record.envelopeJson)
                if (envelope.type != ReplayProtocol.REPLAY_REQUESTED) throw ContractViolation("not a ReplayRequested")
                if (envelope.service != topic.consumer) throw ContractViolation("request not from the topic's consumer")
                request = json.readRequest(envelope.dataJson)
            } catch (violation: ContractViolation) {
                deadLetter(topic.topic, record, violation.message ?: "contract violation")
                return null
            }
            val addressed =
                request.producer == service ||
                    (
                        request.producer == CatalogueEntry.EVERY_SERVICE &&
                            CatalogueEntry.EVERY_SERVICE in topic.producers
                    )
            if (!addressed) {
                finish(topic.topic, record, envelope) {}
                return null
            }
            val replay = catalogue.replayTopic(request.producer, topic.consumer)
            if (replay == null) {
                deadLetter(topic.topic, record, "no replay topic for (${request.producer}, ${topic.consumer})")
                return null
            }
            if (isDuplicate(envelope)) {
                finish(topic.topic, record, envelope) {}
                return null
            }
            val correlation = CorrelationId("replay:${request.replayId}")
            val causation = CausationId(envelope.id.value)
            var count = 0L
            for (source in replay.eventTypes.sorted().mapNotNull { sourcesByType[it] }) {
                val tenants = request.scope?.onlyTenant?.let(::listOf) ?: source.tenants()
                for (tenant in tenants) {
                    var cursor: String? = null
                    do {
                        val page =
                            inTenant(tenant) {
                                val page = source.page(request.scope, cursor, pageSize)
                                require(
                                    page.items.all { it.type == source.eventType },
                                ) { "a state source returns its own type" }
                                writer.appendReplayed(replay.topic, page.items, tenant, correlation, causation)
                                page
                            }
                        count += page.items.size
                        cursor = page.next
                    } while (cursor != null)
                }
            }
            val written =
                finish(topic.topic, record, envelope) {
                    writer.appendCompleted(replay.topic, request.replayId, count, correlation, causation)
                }
            return if (written) ReplayAnswer(request.replayId, topic.consumer, replay.topic, count) else null
        }

        /**
         * Under the platform tenant: the request's inbox row and the position past it, and [complete] (the marker) only
         * when this transaction is the one that takes the inbox row. False when another run had already handled it.
         */
        private fun finish(
            topic: String,
            record: LogRecord,
            envelope: Envelope,
            complete: () -> Unit,
        ): Boolean =
            inTenant(TenantId.PLATFORM) {
                if (record.offset < lockOffset(topic)) return@inTenant false
                val claimed = claimInbox(envelope)
                if (claimed) complete()
                advance(topic, record.offset)
                claimed
            }

        private fun isDuplicate(envelope: Envelope): Boolean =
            inTenant(TenantId.PLATFORM) {
                dsl
                    .selectOne()
                    .from(inbox)
                    .where(CONSUMER.eq(service).and(EVENT_ID.eq(envelope.id.value)))
                    .fetchOne() != null
            }

        /** [block] in a transaction of its own bound to [tenant], on a connection of its own. */
        private fun <T> inTenant(
            tenant: TenantId,
            block: () -> T,
        ): T =
            scope.transactions.outsideOpenTransaction {
                scope.bindWorkload(tenant) { scope.transactions.inTransaction(block) }
            }

        private fun claimInbox(envelope: Envelope): Boolean =
            dsl
                .insertInto(inbox, CONSUMER, EVENT_ID, TENANT_ID)
                .values(
                    DSL.`val`(service, SQLDataType.CLOB),
                    DSL.`val`(envelope.id.value, SQLDataType.CLOB),
                    DSL.`val`(envelope.tenant.value, SQLDataType.UUID),
                ).onConflictDoNothing()
                .execute() == 1

        /** The service's position on [topic], in a transaction of its own (the offset table holds no tenant data). */
        private fun position(topic: String): Long =
            scope.platformTransaction {
                dsl
                    .select(NEXT_OFFSET)
                    .from(offsets)
                    .where(CONSUMER.eq(service).and(TOPIC.eq(topic)))
                    .fetchOne()
                    ?.get(NEXT_OFFSET) ?: FIRST_OFFSET
            }

        private fun lockOffset(topic: String): Long {
            dsl
                .insertInto(offsets, CONSUMER, TOPIC, NEXT_OFFSET)
                .values(
                    DSL.`val`(service, SQLDataType.CLOB),
                    DSL.`val`(topic, SQLDataType.CLOB),
                    DSL.`val`(FIRST_OFFSET, SQLDataType.BIGINT),
                ).onConflictDoNothing()
                .execute()
            val row =
                dsl
                    .select(NEXT_OFFSET)
                    .from(offsets)
                    .where(CONSUMER.eq(service).and(TOPIC.eq(topic)))
                    .forUpdate()
                    .fetchOne()
            return checkNotNull(row) { "offset row missing" }.get(NEXT_OFFSET)
        }

        private fun advance(
            topic: String,
            offset: Long,
        ) {
            dsl
                .update(offsets)
                .set(NEXT_OFFSET, DSL.`val`(offset + 1, SQLDataType.BIGINT))
                .where(CONSUMER.eq(service).and(TOPIC.eq(topic)))
                .execute()
        }

        /**
         * Records the dead letter and moves the position past it in one runtime-login transaction, the letter through the
         * `<svc>_sys`-owned [OutboxSchema.DEAD_LETTER_RECORD] (a letter may have no tenant, so no tenant policy admits it).
         */
        private fun deadLetter(
            topic: String,
            record: LogRecord,
            reason: String,
        ) {
            scope.platformTransaction {
                val letter =
                    DSL.function(
                        DSL.name(s, OutboxSchema.DEAD_LETTER_RECORD),
                        SQLDataType.OTHER,
                        DSL.`val`(service, SQLDataType.CLOB),
                        DSL.`val`(topic, SQLDataType.CLOB),
                        DSL.`val`(record.offset, SQLDataType.BIGINT),
                        DSL.`val`(envelopes.tenantOf(record.envelopeJson)?.value, SQLDataType.UUID),
                        DSL.`val`(reason, SQLDataType.CLOB),
                        DSL.`val`(record.envelopeJson, SQLDataType.CLOB),
                    )
                dsl.select(letter).fetch()
                dsl
                    .insertInto(offsets, CONSUMER, TOPIC, NEXT_OFFSET)
                    .values(
                        DSL.`val`(service, SQLDataType.CLOB),
                        DSL.`val`(topic, SQLDataType.CLOB),
                        DSL.`val`(record.offset + 1, SQLDataType.BIGINT),
                    ).onConflict(CONSUMER, TOPIC)
                    .doUpdate()
                    .set(NEXT_OFFSET, DSL.greatest(storedNext, DSL.excluded(NEXT_OFFSET)))
                    .execute()
            }
        }

        /** The per-topic transaction advisory lock, a jOOQ function call; false when another run holds it. */
        private fun tryLock(topic: String): Boolean {
            val lock = JooqRoutines.tryAdvisoryXactLock("$s/replay-responder/$topic")
            return dsl.select(lock).fetchSingle().get(lock) == true
        }

        private companion object {
            val CONSUMER = DSL.field(DSL.name("consumer"), SQLDataType.CLOB)
            val TOPIC = DSL.field(DSL.name("topic"), SQLDataType.CLOB)
            val NEXT_OFFSET = DSL.field(DSL.name("next_offset"), SQLDataType.BIGINT)
            val EVENT_ID = DSL.field(DSL.name("event_id"), SQLDataType.CLOB)
            val TENANT_ID = DSL.field(DSL.name("tenant_id"), SQLDataType.UUID)
            const val FIRST_OFFSET = 1L
            const val BATCH = 20
            const val DEFAULT_PAGE = 200
            const val MAX_PAGE = 5000
        }
    }
