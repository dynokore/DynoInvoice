package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * The consumer side of a replay request (INV-REQ-1205 b4, b6; owner ruling INV-Q-3599): the steps service-runtime's
 * `rebuildConsumer` runs, all in the caller's one transaction under the platform tenant (INV-REQ-1226 b7), so the head
 * move and the request commit together or not at all (INV-AC-3657):
 * - [request] moves the consumer's position on each named topic past its head (and past its purge watermark), records
 *   the pending replay and writes `ReplayRequested` to the service's own outbox; its relay publishes it on
 *   `gst.<consumer>.replay-requested.v1`;
 * - a topic with several catalogue producers is addressed to all of them under one replay id (owner ruling
 *   INV-Q-3788 A): one head move and one `ReplayRequested` per producer;
 * - [pending] and [isBehind] answer the operation's repeat rule (INV-Q-3665) and its precondition (EVL-003).
 *
 * Every statement is jOOQ on [dsl] (INV-T-5306), which runs in the caller's open transaction; [head] reads topic heads and
 * watermarks through `eventlog.claim` and the watermark rows on the service's runtime login (INV-Q-4264).
 */
class JdbcConsumerReplay(
    schema: String,
    private val dsl: DSLContext,
    private val head: EventLogHead,
    private val outbox: OutboxWriter,
    private val clock: Clock,
) {
    private val s = SqlIdentifier.require(schema, "schema")
    private val json = ReplayJson()
    private val replays: Table<*> = DSL.table(DSL.name(s, OutboxSchema.CONSUMER_REPLAY))
    private val offsets: Table<*> = DSL.table(DSL.name(s, OutboxSchema.CONSUMER_OFFSET))

    /**
     * The pending replay of the ([consumer], [producer]) pair, or `null`. A replay stays pending until its incident
     * closes, also after this pair's producer has completed (INV-Q-3845): a set addressed to several producers is one
     * replay until every addressee has completed and the incident has closed (INV-Q-3788 A, INV-Q-3665).
     */
    fun pending(
        consumer: String,
        producer: String,
    ): PendingReplay? =
        dsl
            .select(REPLAY_ID, CONSUMER, PRODUCER, MODE, SCOPE, TOPICS, REQUESTED_AT)
            .from(replays)
            .where(CONSUMER.eq(consumer).and(PRODUCER.eq(producer)).and(CLOSED_AT.isNull))
            .fetchOne(::pendingOf)

    /**
     * True when [consumer]'s next position on [topic] (its committed offset plus one; 1 before any) is at or below the
     * topic's purge watermark: the consumer holds the topic in 'rebuild required' (INV-REQ-1205 b6).
     */
    fun isBehind(
        consumer: String,
        topic: String,
    ): Boolean {
        val next = nextOffset(consumer, topic, lock = false)
        return next <= head.head(topic, maxOf(next - 1, 0)).watermark
    }

    /** The addressees of [consumer]'s replay [replayId], completed or not: one per producer it was requested from. */
    fun addressees(
        consumer: String,
        replayId: UUID,
    ): List<String> =
        dsl
            .select(PRODUCER)
            .from(replays)
            .where(CONSUMER.eq(consumer).and(REPLAY_ID.eq(replayId)))
            .orderBy(PRODUCER)
            .fetch(PRODUCER)

    /**
     * Moves [consumer]'s position on each of [topics] past its head, records [request] as the pair's pending replay and
     * writes its `ReplayRequested` (the consumer service is its CloudEvents source) through [outbox], in the caller's
     * open transaction under the platform tenant. Returns the positions before and after the move (the audit's prior
     * state, INV-Q-3663). The caller has checked [pending] first; a second pending replay for the pair fails the
     * transaction (unique index).
     */
    fun request(
        consumer: String,
        topics: List<String>,
        request: ReplayRequest,
        correlation: CorrelationId,
        causation: CausationId,
    ): List<MovedPosition> = request(consumer, topics, listOf(request), correlation, causation)

    /**
     * [request] for several addressees under one replay id (owner ruling INV-Q-3788 A): a topic with several catalogue
     * producers is addressed to every one of them in one transaction. [topics] move to the head once; each of
     * [requests] (one per producer, all with the same replay id, mode and scope) is recorded as its pair's pending
     * replay and written as its own `ReplayRequested` naming that one producer.
     */
    fun request(
        consumer: String,
        topics: List<String>,
        requests: List<ReplayRequest>,
        correlation: CorrelationId,
        causation: CausationId,
    ): List<MovedPosition> {
        require(topics.isNotEmpty()) { "a replay moves at least one topic" }
        requireOneReplay(requests)
        val moved =
            topics.distinct().sorted().map { topic ->
                val before = nextOffset(consumer, topic, lock = true)
                // the head is searched from the consumer's committed position (INV-Q-4264)
                val after = maxOf(before, head.head(topic, maxOf(before - 1, 0)).nextAfterHead)
                moveTo(consumer, topic, after)
                MovedPosition(topic, before, after)
            }
        requests.forEach { record(consumer, it, moved.map { m -> m.topic }) }
        outbox.append(
            requests.map { OutboundEvent(ReplayProtocol.REPLAY_REQUESTED, json.writeRequest(it)) },
            EventContext(
                tenant = TenantId.PLATFORM,
                entity = EntityRef.PLATFORM,
                docUuid = null,
                transactionDate = null,
                aggregateKey = "replay/${requests.first().replayId}",
                aggregateVersion = null,
                correlation = correlation,
                causation = causation,
            ),
        )
        return moved
    }

    /**
     * The full sync of [consumer] from [request]'s producer (coordinator rulings INV-Q-3603, INV-Q-3786, INV-Q-4064), in
     * the caller's one transaction on the runtime login bound to the platform tenant: runs [clear] through the read-model
     * tables' sys-owned clear functions (every tenant), deletes the consumer's docsequence rows for that producer through
     * `full_sync_delete` (every tenant; none for 'every service', INV-Q-3805), keeps the inbox, then moves [topics] to the
     * head and writes the request as [request] does. A crash before the commit leaves none of it.
     */
    fun fullSync(
        consumer: String,
        topics: List<String>,
        request: ReplayRequest,
        clear: ReadModelClear?,
        correlation: CorrelationId,
        causation: CausationId,
    ): List<MovedPosition> = fullSync(consumer, topics, listOf(request), listOfNotNull(clear), correlation, causation)

    /**
     * [fullSync] addressed to several producers under one replay id (owner ruling INV-Q-3788 A): runs every one of
     * [clears] (one per producer of [requests]), deletes the consumer's docsequence rows of every addressee (INV-Q-3603,
     * coordinator ruling INV-Q-3843; none for 'every service', INV-Q-3805), moves [topics] to the head once and writes
     * one request per producer.
     */
    fun fullSync(
        consumer: String,
        topics: List<String>,
        requests: List<ReplayRequest>,
        clears: List<ReadModelClear>,
        correlation: CorrelationId,
        causation: CausationId,
    ): List<MovedPosition> {
        requireOneReplay(requests)
        require(requests.first().mode == ReplayMode.FULL_SYNC) { "a full sync request" }
        val producers = requests.map { it.producer }.toSet()
        require(clears.all { it.producer in producers }) { "the clear of a producer not addressed" }
        clears.flatMap { it.tables }.forEach(::clearTable)
        // Each addressee's docsequence rows are deleted, as INV-Q-3603 does per pair (coordinator ruling INV-Q-3843), so
        // its replayed states are applied, not STALE. An 'every service' full sync deletes none (INV-Q-3805): the events
        // on those topics write none, and deleting by source would strip other producers' guards.
        producers.filter { it != CatalogueEntry.EVERY_SERVICE }.forEach { producer ->
            dsl.select(JooqRoutines.fullSyncDelete(s, consumer, "/gst/$producer")).fetch()
        }
        return request(consumer, topics, requests, correlation, causation)
    }

    /**
     * Calls [table]'s sys-owned clear function (`OutboxSchema.readModelClearFunction`) with its filter value as a bound
     * parameter of unspecified type, so the server takes the type of the function's filter column.
     */
    private fun clearTable(table: ReadModelTable) {
        dsl.select(JooqRoutines.readModelClear(s, table.table, table.filterValue)).fetch()
    }

    private fun requireOneReplay(requests: List<ReplayRequest>) {
        require(requests.isNotEmpty()) { "a replay has at least one addressee" }
        val first = requests.first()
        require(requests.all { it.replayId == first.replayId && it.mode == first.mode && it.scope == first.scope }) {
            "the requests of one replay share its id, mode and scope"
        }
        require(requests.map { it.producer }.toSet().size == requests.size) { "one request per producer" }
    }

    private fun record(
        consumer: String,
        request: ReplayRequest,
        topics: List<String>,
    ) {
        dsl
            .insertInto(replays)
            .columns(REPLAY_ID, CONSUMER, PRODUCER, MODE, SCOPE, TOPICS, REQUESTED_AT)
            .values(
                request.replayId,
                consumer,
                request.producer,
                request.mode.name,
                request.scope?.let(json::writeScope),
                topics.toTypedArray(),
                clock.instant().atOffset(ZoneOffset.UTC),
            ).execute()
    }

    private fun nextOffset(
        consumer: String,
        topic: String,
        lock: Boolean,
    ): Long {
        val query = dsl.select(NEXT_OFFSET).from(offsets).where(CONSUMER.eq(consumer).and(TOPIC.eq(topic)))
        return (if (lock) query.forUpdate() else query).fetchOne(NEXT_OFFSET) ?: FIRST_OFFSET
    }

    private fun moveTo(
        consumer: String,
        topic: String,
        next: Long,
    ) {
        dsl
            .insertInto(offsets)
            .columns(CONSUMER, TOPIC, NEXT_OFFSET)
            .values(consumer, topic, next)
            .onConflict(CONSUMER, TOPIC)
            .doUpdate()
            .set(NEXT_OFFSET, DSL.excluded(NEXT_OFFSET))
            .set(ATTEMPTS, 0)
            .set(INFRA_FAILURES, 0)
            .set(FIRST_INFRA_FAILURE_AT, DSL.`val`(null, SQLDataType.TIMESTAMPWITHTIMEZONE))
            .set(RETRY_NOT_BEFORE, DSL.`val`(null, SQLDataType.TIMESTAMPWITHTIMEZONE))
            .execute()
    }

    private fun pendingOf(row: Record): PendingReplay =
        PendingReplay(
            replayId = row.get(REPLAY_ID),
            consumer = row.get(CONSUMER),
            producer = row.get(PRODUCER),
            mode = ReplayMode.valueOf(row.get(MODE)),
            scope = row.get(SCOPE)?.let(json::readScope),
            topics = row.get(TOPICS).toList(),
            requestedAt = row.get(REQUESTED_AT).toInstant(),
        )

    private companion object {
        const val FIRST_OFFSET = 1L
        val REPLAY_ID = DSL.field(DSL.name("replay_id"), SQLDataType.UUID.notNull())
        val CONSUMER = DSL.field(DSL.name("consumer"), SQLDataType.CLOB.notNull())
        val PRODUCER = DSL.field(DSL.name("producer"), SQLDataType.CLOB.notNull())
        val MODE = DSL.field(DSL.name("mode"), SQLDataType.CLOB.notNull())
        val SCOPE = DSL.field(DSL.name("scope"), SQLDataType.CLOB)
        val TOPICS = DSL.field(DSL.name("topics"), SQLDataType.CLOB.array().notNull())
        val REQUESTED_AT = DSL.field(DSL.name("requested_at"), SQLDataType.TIMESTAMPWITHTIMEZONE.notNull())
        val CLOSED_AT = DSL.field(DSL.name("closed_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
        val TOPIC = DSL.field(DSL.name("topic"), SQLDataType.CLOB.notNull())
        val NEXT_OFFSET = DSL.field(DSL.name("next_offset"), SQLDataType.BIGINT.notNull())
        val ATTEMPTS = DSL.field(DSL.name("attempts"), SQLDataType.INTEGER)
        val INFRA_FAILURES = DSL.field(DSL.name("infra_failures"), SQLDataType.INTEGER)
        val FIRST_INFRA_FAILURE_AT = DSL.field(DSL.name("first_infra_failure_at"), SQLDataType.TIMESTAMPWITHTIMEZONE)
        val RETRY_NOT_BEFORE = DSL.field(DSL.name("retry_not_before"), SQLDataType.TIMESTAMPWITHTIMEZONE)
    }
}
