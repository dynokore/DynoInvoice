package com.dynokore.platform.serviceruntime.staleness

import com.dynokore.platform.outboxandqueue.ConsumerStaleness
import com.dynokore.platform.outboxandqueue.StalenessMonitor
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.time.Duration

/** A monitor that cannot measure reads as an absent lag gauge (caught by INV-T-3035's absent rule), never as zero. */
class StalenessGaugesTest {
    @Test
    fun `a failed measurement withdraws the lag gauges until the next one succeeds`() {
        val rules = StalenessRules.resolve(listOf(StalenessProperties.Rule(CONSUMER, CANONICAL))).rules
        var failing = false
        val monitor =
            object : StalenessMonitor {
                override fun check(): List<ConsumerStaleness> {
                    check(!failing) { "database unavailable" }
                    return listOf(ConsumerStaleness(CONSUMER, CANONICAL, 1, null, Duration.ofSeconds(42), Duration.ofMinutes(5)))
                }
            }
        val registry = SimpleMeterRegistry()
        val gauges = StalenessGauges(monitor, rules, registry)

        gauges.refresh().shouldNotBeNull()
        registry.get(LAG_NAME).gauge().value() shouldBe 42.0
        registry.meters.map { it.id.name }.sorted() shouldBe listOf(LAG_NAME, THRESHOLD_NAME)
        registry.meters.forEach { meter -> meter.id.tags.map { it.key }.toSet() shouldBe setOf("consumer", "event_type") }

        failing = true
        gauges.refresh().shouldBeNull()
        registry.find(LAG_NAME).gauge().shouldBeNull()
        registry.get(THRESHOLD_NAME).gauge().value() shouldBe 300.0

        failing = false
        gauges.refresh().shouldNotBeNull()
        registry.get(LAG_NAME).gauge().value() shouldBe 42.0
    }

    private companion object {
        // the literal names INV-T-3035's alert rules read (INV-Q-3831 A), never the constants
        const val LAG_NAME = "gst.platform.consumer.staleness.lag"
        const val THRESHOLD_NAME = "gst.platform.consumer.staleness.threshold"
        const val CONSUMER = "submission-svc"
        const val CANONICAL = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1"
    }
}
