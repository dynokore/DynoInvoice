package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantId
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.util.UUID

/**
 * The consumer side of a replay request (INV-REQ-1205 b4, b6; owner ruling INV-Q-3599): the steps service-runtime's
 * `rebuildConsumer` runs, all in the caller's one transaction under the platform tenant (INV-REQ-1226 b7), so the head
 * move and the request commit together or not at all (INV-AC-3657):
 * - [request] moves the consumer's position on each named topic past its head (and past its purge watermark), records
 *   the pending replay and writes `ReplayRequested` to the service's own outbox; its relay publishes it on
 *   `gst.<consumer>.replay-requested.v1`;
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

    /** The pending replay of the ([consumer], [producer]) pair, or `null`. */
    fun pending(
        consumer: String,
        producer: String,
    ): PendingReplay? = connections.require().prepareStatement(
        "select replay_id, consumer, producer, mode, scope, topics, requested_at from $s.${OutboxSchema.CONSUMER_REPLAY} " +
            "where consumer = ? and producer = ? and completed_at is null",
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
    ): List<MovedPosition> {
        require(topics.isNotEmpty()) { "a replay moves at least one topic" }
        val connection = connections.require()
        val moved =
            topics.distinct().sorted().map { topic ->
                val before = nextOffset(connection, consumer, topic, lock = true)
                val after = maxOf(before, head.head(topic).nextAfterHead)
                moveTo(connection, consumer, topic, after)
                MovedPosition(topic, before, after)
            }
        connection.prepareStatement(
            "insert into $s.${OutboxSchema.CONSUMER_REPLAY} (replay_id, consumer, producer, mode, scope, topics, requested_at) " +
                "values (?, ?, ?, ?, ?, ?, ?)",
        ).use {
            it.setObject(1, request.replayId)
            it.setString(2, consumer)
            it.setString(3, request.producer)
            it.setString(4, request.mode.name)
            it.setString(5, request.scope?.let(json::writeScope))
            it.setArray(6, connection.createArrayOf("text", moved.map { m -> m.topic }.toTypedArray()))
            it.setTimestamp(7, Timestamp.from(clock.instant()))
            it.executeUpdate()
        }
        outbox.append(
            listOf(OutboundEvent(ReplayProtocol.REPLAY_REQUESTED, json.writeRequest(request))),
            EventContext(
                tenant = TenantId.PLATFORM,
                entity = EntityRef.PLATFORM,
                docUuid = null,
                transactionDate = null,
                aggregateKey = "replay/${request.replayId}",
                aggregateVersion = null,
                correlation = correlation,
                causation = causation,
            ),
        )
        return moved
    }

    /**
     * The full sync of [consumer] from [request]'s producer (coordinator rulings INV-Q-3603, INV-Q-3786), in the
     * caller's one transaction on the consumer's worker-role connection bound to the platform tenant: runs [clear] (the
     * read model built from the producer's topics, every tenant), deletes the consumer's docsequence rows for that
     * producer (every tenant; for 'every service', every source), keeps the inbox, then moves [topics] to the head and
     * writes the request as [request] does. A crash before the commit leaves none of it.
     */
    fun fullSync(
        consumer: String,
        topics: List<String>,
        request: ReplayRequest,
        clear: ReadModelClear?,
        correlation: CorrelationId,
        causation: CausationId,
    ): List<MovedPosition> {
        require(request.mode == ReplayMode.FULL_SYNC) { "a full sync request" }
        require(clear == null || clear.producer == request.producer) { "the clear of another producer" }
        val connection = connections.require()
        clear?.statements?.forEach { sql -> connection.createStatement().use { it.executeUpdate(sql) } }
        val bySource = if (request.producer == CatalogueEntry.EVERY_SERVICE) "" else " and source = ?"
        connection.prepareStatement("delete from $s.${OutboxSchema.CONSUMER_SEQUENCE} where consumer = ?$bySource").use {
            it.setString(1, consumer)
            if (bySource.isNotEmpty()) it.setString(2, "/gst/${request.producer}")
            it.executeUpdate()
        }
        return request(consumer, topics, request, correlation, causation)
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
