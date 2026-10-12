package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantId
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Clock
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
 * [connections] is the app role's current connection (the caller's transaction); [head] reads topic heads and
 * watermarks through the consumer's event-log claim role.
 */
class JdbcConsumerReplay(
    schema: String,
    private val connections: CurrentConnection,
    private val head: EventLogHead,
    private val outbox: OutboxWriter,
    private val clock: Clock,
) {
    private val s = SqlIdentifier.require(schema, "schema")
    private val json = ReplayJson()

    /**
     * The pending replay of the ([consumer], [producer]) pair, or `null`. A replay stays pending until its incident
     * closes, also after this pair's producer has completed (INV-Q-3845): a set addressed to several producers is one
     * replay until every addressee has completed and the incident has closed (INV-Q-3788 A, INV-Q-3665).
     */
    fun pending(
        consumer: String,
        producer: String,
    ): PendingReplay? = connections.require().prepareStatement(
        "select replay_id, consumer, producer, mode, scope, topics, requested_at from $s.${OutboxSchema.CONSUMER_REPLAY} " +
            "where consumer = ? and producer = ? and closed_at is null",
    ).use {
        it.setString(1, consumer)
        it.setString(2, producer)
        it.executeQuery().use { rows -> if (rows.next()) pendingOf(rows) else null }
    }

    /**
     * True when [consumer]'s next position on [topic] (its committed offset plus one; 1 before any) is at or below the
     * topic's purge watermark: the consumer holds the topic in 'rebuild required' (INV-REQ-1205 b6).
     */
    fun isBehind(
        consumer: String,
        topic: String,
    ): Boolean = nextOffset(connections.require(), consumer, topic, lock = false) <= head.head(topic).watermark

    /** The addressees of [consumer]'s replay [replayId], completed or not: one per producer it was requested from. */
    fun addressees(
        consumer: String,
        replayId: UUID,
    ): List<String> = connections.require().prepareStatement(
        "select producer from $s.${OutboxSchema.CONSUMER_REPLAY} where consumer = ? and replay_id = ? order by producer",
    ).use {
        it.setString(1, consumer)
        it.setObject(2, replayId)
        it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }

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
        val connection = connections.require()
        val moved =
            topics.distinct().sorted().map { topic ->
                val before = nextOffset(connection, consumer, topic, lock = true)
                val after = maxOf(before, head.head(topic).nextAfterHead)
                moveTo(connection, consumer, topic, after)
                MovedPosition(topic, before, after)
            }
        requests.forEach { record(connection, consumer, it, moved.map { m -> m.topic }) }
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
        val connection = connections.require()
        clears.flatMap { it.tables }.forEach { clearTable(connection, it) }
        // Each addressee's docsequence rows are deleted, as INV-Q-3603 does per pair (coordinator ruling INV-Q-3843), so
        // its replayed states are applied, not STALE. An 'every service' full sync deletes none (INV-Q-3805): the events
        // on those topics write none, and deleting by source would strip other producers' guards.
        producers.filter { it != CatalogueEntry.EVERY_SERVICE }.forEach { producer ->
            connection.prepareStatement("select $s.${OutboxSchema.FULL_SYNC_DELETE}(?, ?)").use {
                it.setString(1, consumer)
                it.setString(2, "/gst/$producer")
                it.executeQuery().close()
            }
        }
        return request(consumer, topics, requests, correlation, causation)
    }

    /**
     * Calls [table]'s sys-owned clear function (`OutboxSchema.readModelClearFunction`) with its filter value as a bound
     * parameter of unspecified type, so the server takes the type of the function's filter column.
     */
    private fun clearTable(
        connection: Connection,
        table: ReadModelTable,
    ) {
        val function = "$s.${OutboxSchema.readModelClearFunctionName(table.table)}"
        val filter = table.filterValue
        if (filter == null) {
            connection.prepareStatement("select $function()").use { it.executeQuery().close() }
        } else {
            connection.prepareStatement("select $function(?)").use {
                it.setObject(1, filter, Types.OTHER)
                it.executeQuery().close()
            }
        }
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
        connection: Connection,
        consumer: String,
        request: ReplayRequest,
        topics: List<String>,
    ) {
        connection.prepareStatement(
            "insert into $s.${OutboxSchema.CONSUMER_REPLAY} (replay_id, consumer, producer, mode, scope, topics, requested_at) " +
                "values (?, ?, ?, ?, ?, ?, ?)",
        ).use {
            it.setObject(1, request.replayId)
            it.setString(2, consumer)
            it.setString(3, request.producer)
            it.setString(4, request.mode.name)
            it.setString(5, request.scope?.let(json::writeScope))
            it.setArray(6, connection.createArrayOf("text", topics.toTypedArray()))
            it.setTimestamp(7, Timestamp.from(clock.instant()))
            it.executeUpdate()
        }
    }

    private fun nextOffset(
        connection: Connection,
        consumer: String,
        topic: String,
        lock: Boolean,
    ): Long = connection.prepareStatement(
        "select next_offset from $s.${OutboxSchema.CONSUMER_OFFSET} where consumer = ? and topic = ?" +
            if (lock) " for update" else "",
    ).use {
        it.setString(1, consumer)
        it.setString(2, topic)
        it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else FIRST_OFFSET }
    }

    private fun moveTo(
        connection: Connection,
        consumer: String,
        topic: String,
        next: Long,
    ) {
        connection.prepareStatement(
            "insert into $s.${OutboxSchema.CONSUMER_OFFSET} (consumer, topic, next_offset) values (?, ?, ?) " +
                "on conflict (consumer, topic) do update set next_offset = excluded.next_offset, attempts = 0, " +
                "infra_failures = 0, first_infra_failure_at = null, retry_not_before = null",
        ).use {
            it.setString(1, consumer)
            it.setString(2, topic)
            it.setLong(3, next)
            it.executeUpdate()
        }
    }

    private fun pendingOf(rows: ResultSet): PendingReplay {
        @Suppress("UNCHECKED_CAST")
        val topics = (rows.getArray(6).array as Array<String>).toList()
        return PendingReplay(
            replayId = rows.getObject(1, UUID::class.java),
            consumer = rows.getString(2),
            producer = rows.getString(3),
            mode = ReplayMode.valueOf(rows.getString(4)),
            scope = rows.getString(5)?.let(json::readScope),
            topics = topics,
            requestedAt = rows.getTimestamp(7).toInstant(),
        )
    }

    private companion object {
        const val FIRST_OFFSET = 1L
    }
}
