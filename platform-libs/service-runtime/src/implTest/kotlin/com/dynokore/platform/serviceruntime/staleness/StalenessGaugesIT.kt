package com.dynokore.platform.serviceruntime.staleness

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogSchema
import com.dynokore.platform.outboxandqueue.JdbcStalenessMonitor
import com.dynokore.platform.outboxandqueue.OutboxPostgresFixture
import com.dynokore.platform.outboxandqueue.PostgresEventLogReader
import com.dynokore.platform.outboxandqueue.StalenessBreach
import com.dynokore.platform.serviceruntime.jobs.MutableClock
import com.dynokore.platform.tenancykernel.TenantContextImpl
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Duration
import java.time.Instant

/**
 * INV-T-3005 over PostgreSQL 17 and 18 with outbox-and-queue's [JdbcStalenessMonitor]: a consumer that stops
 * consuming reports a lag gauge that passes the threshold gauge beside it, labelled with consumer and event type
 * only (INV-AC-2150, INV-REQ-1165 b3; the alert rule on these gauges is INV-T-3035's, INV-Q-3030 (a)).
 */
class StalenessGaugesIT {
    @ParameterizedTest(name = "{displayName}[{0}]")
    @ValueSource(strings = ["postgres:17", "postgres:18"])
    fun `a stopped consumer's lag gauge passes its threshold gauge, and not before`(image: String) {
        OutboxPostgresFixture(image).use { fixture ->
            fixture.createService(SCHEMA)
            fixture.createTopics(CATALOGUE)
            val clock = MutableClock(T0)
            val breaches = mutableListOf<StalenessBreach>()
            val rules =
                StalenessRules.resolve(
                    listOf(StalenessProperties.Rule(CONSUMER, CANONICAL), StalenessProperties.Rule(CONSUMER, POLICY)),
                ).rules
            val monitor =
                JdbcStalenessMonitor(
                    fixture.runtimeDataSource(),
                    PostgresEventLogReader(fixture.eventLogDataSource(), fixture.eventLogScope(TenantContextImpl())),
                    CATALOGUE,
                    SCHEMA,
                    rules,
                    clock,
                ) { breaches += it }
            val registry = SimpleMeterRegistry()
            val gauges = StalenessGauges(monitor, rules, registry)

            // nothing unconsumed: no lag; thresholds are the configured (provisional) values
            gauges.refresh()
            lag(registry, CANONICAL) shouldBe 0.0
            lag(registry, POLICY) shouldBe 0.0
            threshold(registry, CANONICAL) shouldBe 300.0
            threshold(registry, POLICY) shouldBe 1800.0
            // exactly two series per name, one per rule, each labelled consumer and event_type only (CS-36)
            listOf(LAG_NAME, THRESHOLD_NAME).forEach { name ->
                val found = registry.meters.filter { it.id.name == name }
                found.size shouldBe 2
                found.forEach { meter ->
                    meter.id.tags.map { it.key } shouldContainExactlyInAnyOrder listOf("consumer", "event_type")
                    meter.id.baseUnit shouldBe "seconds"
                }
            }
            registry.meters.map { it.id.name }.toSet() shouldBe setOf(LAG_NAME, THRESHOLD_NAME)

            // submission-svc is stopped while CanonicalVersionCreated events keep arriving, the oldest at T0
            repeat(3) { insertEvent(fixture, CANONICAL_TOPIC, T0.plusSeconds(it.toLong())) }

            clock.advance(Duration.ofMinutes(4))
            gauges.refresh()
            lag(registry, CANONICAL) shouldBe 240.0
            (lag(registry, CANONICAL) > threshold(registry, CANONICAL)) shouldBe false
            breaches shouldBe emptyList()

            clock.advance(Duration.ofMinutes(2))
            gauges.refresh()
            lag(registry, CANONICAL) shouldBe 360.0
            (lag(registry, CANONICAL) > threshold(registry, CANONICAL)) shouldBe true
            lag(registry, POLICY) shouldBe 0.0
            breaches.single().consumer shouldBe CONSUMER
            breaches.single().eventType shouldBe CANONICAL
        }
    }

    private fun gauge(
        registry: SimpleMeterRegistry,
        name: String,
        eventType: String,
    ): Gauge = registry.get(name).tags("consumer", CONSUMER, "event_type", eventType).gauge()

    private fun lag(
        registry: SimpleMeterRegistry,
        eventType: String,
    ) = gauge(registry, LAG_NAME, eventType).value()

    private fun threshold(
        registry: SimpleMeterRegistry,
        eventType: String,
    ) = gauge(registry, THRESHOLD_NAME, eventType).value()

    private fun insertEvent(
        fixture: OutboxPostgresFixture,
        topic: String,
        insertedAt: Instant,
    ) = fixture.runAsAdmin(
        "insert into eventlog.${EventLogSchema.tableName(topic)} (tenant_id, event_id, subject, envelope, inserted_at) " +
            "values ('0a000000-0000-0000-0000-00000000000a', gen_random_uuid()::text, 'doc', '{}'::jsonb, timestamptz '$insertedAt')",
    )

    private companion object {
        // the literal names INV-T-3035's alert rules read (INV-Q-3831 A), never the constants
        const val LAG_NAME = "gst.platform.consumer.staleness.lag"
        const val THRESHOLD_NAME = "gst.platform.consumer.staleness.threshold"
        const val SCHEMA = "submission"
        const val CONSUMER = "submission-svc"
        const val CANONICAL = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1"
        const val CANONICAL_TOPIC = "gst.canonical.canonical-version-created.v1"
        const val POLICY = "com.dynokore.gst.platform.PolicyChanged.v1"
        val T0: Instant = Instant.parse("2026-10-12T00:00:00Z")
        val CATALOGUE =
            EventCatalogue(
                listOf(
                    CatalogueEntry(CANONICAL, CANONICAL_TOPIC, setOf("canonical-svc")),
                    CatalogueEntry(POLICY, "gst.platform.policy-changed.v1", setOf(CatalogueEntry.EVERY_SERVICE)),
                ),
            )
    }
}
