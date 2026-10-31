package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Clock
import java.util.UUID

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
 *   every replayed event. A crash before that leaves the request unhandled: the next run reads it again and restarts
 * the
 *   replay from the start under the same replay id; the consumer finds the re-sent states STALE.
 *
 * A request the service cannot answer (not a valid ReplayRequested, from another service than the topic's consumer, or
 * for a pair without a replay topic) is dead-lettered as a consumer's invalid record is, and the run moves on.
 *
 * Every statement is jOOQ on [dsl] (INV-T-5306): [scope] is the service's runtime login. Positions, dead letters
 * (through [OutboxSchema.DEAD_LETTER_RECORD]) and the per-topic transaction advisory lock (a jOOQ function call, held
 * by
 * a transaction under the platform binding that spans the topic's run, INV-ADR-3968 item 9) each use a connection of
 * their own, outside any open transaction.
 */
class JdbcReplayResponder
    @JvmOverloads
    @Suppress("LongParameterList") // constructor injection under CS-05; INV-Q-4629 (CS-50)
    constructor(
        private val service: String,
        schema: String,
        private val catalogue: EventCatalogue,
        private val reader: EventLogReader,
        private val scope: TenantScope,
        dsl: DSLContext,
        private val writer: JdbcOutboxWriter,
        sources: Collection<ReplayStateSource>,
        private val sink: RebuildRequiredSink,
        private val clock: Clock = Clock.systemUTC(),
        private val pageSize: Int = DEFAULT_PAGE,
    ) {
        private val s = SqlIdentifier.require(schema, "schema")
        private val sourcesByType = sources.associateBy { it.eventType }
        private val envelopes = EnvelopeJson()
        private val json = ReplayJson()
        private val stopped = mutableSetOf<String>()
        private var lastServed = -1
        private var answerLockHeld = false
        private val ledger = ResponderLedger(dsl, scope, s, service, envelopes)
        private val sender = ReplaySender(sourcesByType, writer, scope, pageSize)

        init {
            require(sourcesByType.size == sources.size) { "one state source per event type" }
            require(pageSize in 1..MAX_PAGE) { "page size must be 1..$MAX_PAGE" }
        }

        /**
         * Reads every request topic this service reads and answers what it finds there. Returns the replays answered.
         */
        @Synchronized
        fun runOnce(): List<ReplayAnswer> {
            val topics = catalogue.requestTopicsReadBy(service)
            // fairness: start after the topic served last, wrapping around; the log order within a topic is unchanged
            val start = if (topics.isEmpty()) 0 else (lastServed + 1) % topics.size
            return (topics.indices).flatMap { step ->
                val index = (start + step) % topics.size
                // the lock transaction writes nothing; its end releases the locks
                val answers =
                    scope.bindWorkload(TenantId.PLATFORM) {
                        scope.transactions.inTransaction {
                            answerLockHeld = false
                            if (ledger.tryLock(topics[index].topic)) drain(topics[index]) else emptyList()
                        }
                    }
                if (answers.isNotEmpty()) lastServed = index
                answers
            }
        }

        /**
         * Takes the service-wide answer lock on first need within the open topic transaction (INV-Q-9087): one replay
         * is answered at a time across all instances of the service. False leaves the request unhandled.
         */
        private fun takeAnswerLock(): Boolean {
            if (!answerLockHeld) answerLockHeld = ledger.tryAnswerLock()
            return answerLockHeld
        }

        private fun drain(topic: ReplayRequestTopic): List<ReplayAnswer> {
            val next = ledger.position(topic.topic)
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
            val answers = mutableListOf<ReplayAnswer>()
            for (record in records) {
                val outcome = answer(topic, record)
                if (outcome === BUSY) break // the request stays unhandled; later requests wait for it (log order)
                if (outcome != null) answers += outcome
            }
            return answers
        }

        private fun answer(
            topic: ReplayRequestTopic,
            record: LogRecord,
        ): ReplayAnswer? {
            val parsed = parse(topic, record) ?: return null
            return if (isAddressed(parsed.request, topic)) {
                respond(topic, record, parsed)
            } else {
                finish(topic.topic, record, parsed.envelope) {}
                null
            }
        }

        private class Parsed(
            val envelope: Envelope,
            val request: ReplayRequest,
        )

        /** The request in [record], or null after the record was dead-lettered as not a valid request. */
        private fun parse(
            topic: ReplayRequestTopic,
            record: LogRecord,
        ): Parsed? =
            try {
                val envelope = envelopes.read(record.envelopeJson)
                if (envelope.type != ReplayProtocol.REPLAY_REQUESTED) throw ContractViolation("not a ReplayRequested")
                if (envelope.service != topic.consumer) throw ContractViolation("request not from the topic's consumer")
                Parsed(envelope, json.readRequest(envelope.dataJson))
            } catch (violation: ContractViolation) {
                ledger.deadLetter(topic.topic, record, violation.message ?: "contract violation")
                null
            }

        private fun isAddressed(
            request: ReplayRequest,
            topic: ReplayRequestTopic,
        ): Boolean =
            request.producer == service ||
                (
                    request.producer == CatalogueEntry.EVERY_SERVICE &&
                        CatalogueEntry.EVERY_SERVICE in topic.producers
                )

        private fun respond(
            topic: ReplayRequestTopic,
            record: LogRecord,
            parsed: Parsed,
        ): ReplayAnswer? {
            val (envelope, request) = parsed.envelope to parsed.request
            val replay = catalogue.replayTopic(request.producer, topic.consumer)
            return when {
                replay == null -> {
                    ledger.deadLetter(
                        topic.topic,
                        record,
                        "no replay topic for (${request.producer}, ${topic.consumer})",
                    )
                    null
                }

                ledger.isDuplicate(envelope) -> {
                    finish(topic.topic, record, envelope) {}
                    null
                }

                !takeAnswerLock() -> {
                    BUSY
                }

                else -> {
                    val correlation = CorrelationId("replay:${request.replayId}")
                    val causation = CausationId(envelope.id.value)
                    val count = sender.send(replay, request, correlation, causation)
                    val written =
                        finish(topic.topic, record, envelope) {
                            writer.appendCompleted(replay.topic, request.replayId, count, correlation, causation)
                        }
                    if (written) ReplayAnswer(request.replayId, topic.consumer, replay.topic, count) else null
                }
            }
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
            scope.inTenant(TenantId.PLATFORM) {
                if (record.offset < ledger.lockOffset(topic)) return@inTenant false
                val claimed = ledger.claimInbox(envelope)
                if (claimed) complete()
                ledger.advance(topic, record.offset)
                claimed
            }

        private companion object {
            /** Marks a request left unhandled because another run holds the service's answer lock. */
            val BUSY = ReplayAnswer(UUID(0, 0), "", "", 0)
            const val BATCH = 20
            const val DEFAULT_PAGE = 200
            const val MAX_PAGE = 5000
        }
    }
