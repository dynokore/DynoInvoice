package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.SqlIdentifier
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import java.time.Clock
import java.time.Duration

/**
 * [StalenessMonitor] for the consumers of one service schema (INV-REQ-1165, INV-AC-2150). For each rule it reads
 * the consumer's offset (a platform-bound transaction of [scope], the runtime login, as jOOQ on [dsl]; the offset table holds no tenant data)
 * and asks the event log ([EventLogLag], through `eventlog.claim` only, coordinator ruling INV-Q-4264) for the oldest
 * record the consumer has not consumed and the offset lag. The lag is `now - inserted_at` of that
 * record, measured by the injected [Clock]; it is breached only when strictly greater than the threshold. A breach
 * is raised once and again only after the consumer has recovered, so a stopped consumer does not raise an incident
 * on every check.
 *
 * The metric is returned as plain values: exporting it as a gauge is the composition root's job (service-runtime).
 */
class JdbcStalenessMonitor(
    private val scope: TenantScope,
    private val dsl: DSLContext,
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

    private val offsets = DSL.table(DSL.name(SqlIdentifier.require(schema, "schema"), OutboxSchema.CONSUMER_OFFSET))
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
    override fun check(): List<ConsumerStaleness> = scope.platformTransaction { watched.map { measure(it) } }

    private fun measure(w: Watched): ConsumerStaleness {
        val after = nextOffset(w) - 1
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

    private fun nextOffset(w: Watched): Long =
        dsl
            .select(NEXT_OFFSET)
            .from(offsets)
            .where(CONSUMER.eq(w.rule.consumer).and(TOPIC.eq(w.topic)))
            .fetchOne()
            ?.get(NEXT_OFFSET) ?: 1L

    private companion object {
        val CONSUMER = DSL.field(DSL.name("consumer"), SQLDataType.CLOB)
        val TOPIC = DSL.field(DSL.name("topic"), SQLDataType.CLOB)
        val NEXT_OFFSET = DSL.field(DSL.name("next_offset"), SQLDataType.BIGINT)
    }
}
