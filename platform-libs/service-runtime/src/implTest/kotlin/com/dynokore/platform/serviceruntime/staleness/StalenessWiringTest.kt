package com.dynokore.platform.serviceruntime.staleness

import com.dynokore.platform.outboxandqueue.CatalogueEntry
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.StalenessMonitor
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration
import com.dynokore.platform.serviceruntime.composition.ServiceDataSources
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration
import javax.sql.DataSource

/** The composition root runs the monitor from `service-runtime.staleness.*` and exports its gauges (INV-T-3005). */
class StalenessWiringTest {
    private val runner =
        ApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withConfiguration(
                AutoConfigurations.of(
                    ServiceRuntimeConfiguration::class.java,
                    L1CompositionAutoConfiguration::class.java,
                ),
            ).withPropertyValues(
                "service-runtime.workload-identity.audience=submission-svc",
                "service-runtime.modules.schema=submission",
                "gst.service=submission-svc",
                "service-runtime.staleness.check-interval=1h",
            ).withBean(EventCatalogue::class.java, {
                EventCatalogue(
                    listOf(
                        CatalogueEntry(CANONICAL, "gst.canonical.canonical-version-created.v1", setOf("canonical-svc")),
                    ),
                )
            })
            .withBean(
                "dataSource",
                DataSource::class.java,
                { mockk<DataSource>(relaxed = true) },
                { it.isPrimary = true },
            ).withBean(ServiceDataSources.EVENT_LOG, DataSource::class.java, { mockk<DataSource>(relaxed = true) })

    @Test
    fun `configured rules take their provisional thresholds and are exported as gauges`() {
        runner
            .withPropertyValues(
                "service-runtime.staleness.rules[0].consumer=submission-svc",
                "service-runtime.staleness.rules[0].event-type=$CANONICAL",
            ).run { context ->
                context.startupFailure shouldBe null
                context.getBean(StalenessMonitor::class.java).shouldNotBeNull()
                context
                    .getBean(StalenessRules::class.java)
                    .rules
                    .single()
                    .threshold shouldBe Duration.ofMinutes(5)
                context.getBean(StalenessCheckSchedule::class.java).isRunning shouldBe true
                context
                    .getBean(MeterRegistry::class.java)
                    .get(THRESHOLD_NAME)
                    .tags("consumer", "submission-svc", "event_type", CANONICAL)
                    .gauge()
                    .value() shouldBe 300.0
            }
    }

    @Test
    fun `a longer installation threshold stops start-up, naming the rule`() {
        runner
            .withPropertyValues(
                "service-runtime.staleness.rules[0].consumer=submission-svc",
                "service-runtime.staleness.rules[0].event-type=$CANONICAL",
                "service-runtime.staleness.rules[0].threshold=10m",
            ).run { context ->
                generateSequence(
                    context.startupFailure,
                ) { it.cause }.joinToString { it.message.orEmpty() } shouldContain
                    "consumer submission-svc on $CANONICAL is longer than its provisional PT5M"
            }
    }

    @Test
    fun `without staleness rules there is no monitor`() {
        runner.run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(StalenessMonitor::class.java).size shouldBe 0
            context.getBeansOfType(StalenessGauges::class.java).size shouldBe 0
        }
    }

    private companion object {
        // the literal names INV-T-3035's alert rules read (INV-Q-3831 A), never the constants
        const val LAG_NAME = "gst.platform.consumer.staleness.lag"
        const val THRESHOLD_NAME = "gst.platform.consumer.staleness.threshold"
        const val CANONICAL = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1"
    }
}
