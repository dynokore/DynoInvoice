package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import javax.sql.DataSource

/**
 * [StalenessMonitor] for the consumers of one service schema (INV-REQ-1165, INV-AC-2150). For each rule it reads
 * the consumer's offset (the runtime-login connection [offsetSource]; the offset table holds no tenant data)
 * and asks the event log ([EventLogLag], through `eventlog.claim` only, coordinator ruling INV-Q-4264) for the oldest
 * record the consumer has not consumed and the offset lag. The lag is `now - inserted_at` of that
 * record, measured by the injected [Clock]; it is breached only when strictly greater than the threshold. A breach
 * is raised once and again only after the consumer has recovered, so a stopped consumer does not raise an incident
 * on every check.
 *
 * The metric is returned as plain values: exporting it as a gauge is the composition root's job (service-runtime).
 */
class JdbcStalenessMonitor(
    private val offsetSource: DataSource,
    private val lag: EventLogLag,
    catalogue: EventCatalogue,
    schema: String,
    rules: List<StalenessRule>,
    private val clock: Clock,
    private val sink: StalenessBreachSink,
) : StalenessMonitor {
    private class Watched(
        val rule: StalenessRule,
        val topic: String,
    )

    private val offsets = "${SqlIdentifier.require(schema, "schema")}.${OutboxSchema.CONSUMER_OFFSET}"
    private val watched =
        rules.map { rule ->
            val entry = requireNotNull(catalogue.find(rule.eventType)) { "unknown event type ${rule.eventType}" }
            Watched(rule, entry.topic)
        }
    private val open = HashSet<Pair<String, String>>()

    init {
        require(
            rules.map { it.consumer to it.eventType }.toSet().size == rules.size,
        ) { "one rule per consumer and event type" }
    }

    @Synchronized
    override fun check(): List<ConsumerStaleness> =
        offsetSource.connection.use { connection ->
            watched.map { measure(connection, it) }
        }

    private fun measure(
        connection: Connection,
        w: Watched,
    ): ConsumerStaleness {
        val after = nextOffset(connection, w) - 1
        val backlog = lag.backlog(w.topic, after)
        val now = clock.instant()
        val age =
            backlog.oldestInsertedAt?.let { Duration.between(it, now) }?.coerceAtLeast(Duration.ZERO) ?: Duration.ZERO
        val result =
            ConsumerStaleness(
                w.rule.consumer,
                w.rule.eventType,
                backlog.count,
                backlog.oldestInsertedAt,
                age,
                w.rule.threshold,
            )
        val key = w.rule.consumer to w.rule.eventType
        if (result.breached) {
            if (open.add(
                    key,
                )
            ) {
                sink.raise(StalenessBreach(w.rule.consumer, w.rule.eventType, age, w.rule.threshold, now))
            }
        } else {
            open.remove(key)
        }
        return result
    }

    private fun nextOffset(
        connection: Connection,
        w: Watched,
    ): Long =
        connection.prepareStatement("select next_offset from $offsets where consumer = ? and topic = ?").use {
            it.setString(1, w.rule.consumer)
            it.setString(2, w.topic)
            it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 1L }
        }
}
