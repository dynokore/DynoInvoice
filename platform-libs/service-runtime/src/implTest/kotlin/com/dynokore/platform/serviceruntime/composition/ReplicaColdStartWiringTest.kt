package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogHead
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.datasource.withBindingKey
import com.dynokore.platform.serviceruntime.rebuild.ConsumerRebuildAuthorizer
import com.dynokore.platform.serviceruntime.rebuild.ConsumerRebuildRuntime
import com.dynokore.platform.serviceruntime.rebuild.ReplicaColdStartSync
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.SqlErrors
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.ApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import java.time.Clock
import java.time.Duration

/**
 * INV-T-13608 (P109): [ReplicaColdStartWiring] in the L1 composition defines the fail-closed authorizer, the
 * [ConsumerRebuildRuntime] and the [ReplicaColdStartSync] only when their inputs exist (INV-Q-13599 A, INV-Q-13597 A).
 */
class ReplicaColdStartWiringTest {
    private val runnerStub =
        mockk<TransactionRunner>(moreInterfaces = arrayOf(SqlErrors::class, CurrentConnection::class))
    private val scope = TenantScope(mockk<TenantContext>(), runnerStub, runnerStub as CurrentConnection, "svc-workload")

    /** The L1 composition the way CompositionRootTest starts it, with service-runtime and no event-log inputs. */
    private fun runner() =
        ApplicationContextRunner()
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withBindingKey()
            .withConfiguration(
                AutoConfigurations.of(
                    ServiceRuntimeConfiguration::class.java,
                    L1CompositionAutoConfiguration::class.java,
                ),
            ).withPropertyValues(
                "gst.service=svc",
                "gst.instance-id=svc-0",
                "service-runtime.workload-identity.audience=svc",
                "service-runtime.idempotency.schema=svc",
                "gst.bindings.secret-store.mount-root=/tmp",
                "gst.bindings.telemetry-export=bundled-otel",
                "gst.bindings.telemetry-export-otlp-endpoint=http://collector:4318",
            )

    private fun subscription(schema: String = "svc") = ConsumerSubscription("svc", "gst.some.topic.v1", schema)

    private val withoutSubscription =
        runner()
            .withBean(Clock::class.java, { Clock.systemUTC() })
            .withBean(EventCatalogue::class.java, { EventCatalogue(emptyList()) })
            .withBean(TenantScope::class.java, { scope })
            .withBean(OutboxWriter::class.java, { mockk<OutboxWriter>() })
            .withBean(AuditWriter::class.java, { mockk<AuditWriter>() })
            .withBean(EventLogHead::class.java, { mockk<EventLogHead>() })
            .withBean(AuthorityReplica::class.java, { AuthorityReplica(Clock.systemUTC()) })

    private val base =
        withoutSubscription.withBean(
            "subscription",
            ConsumerSubscription::class.java,
            { subscription() },
        )

    private fun ApplicationContext.count(type: Class<*>) = getBeansOfType(type).size

    private fun ready(context: ConfigurableApplicationContext) =
        context.publishEvent(ApplicationReadyEvent(SpringApplication(), emptyArray(), context, Duration.ZERO))

    @Test
    fun `with all five inputs each of the three types is one bean and the sync starts on the ready event`() {
        base.run { context ->
            context.startupFailure shouldBe null
            context.count(ConsumerRebuildAuthorizer::class.java) shouldBe 1
            context.count(ConsumerRebuildRuntime::class.java) shouldBe 1
            context.count(ReplicaColdStartSync::class.java) shouldBe 1
            val sync = context.getBean(ReplicaColdStartSync::class.java)
            sync.isRunning shouldBe false
            ready(context)
            sync.isRunning shouldBe true
        }
    }

    @Test
    fun `without an EventLogHead the runtime and the sync are no beans, even with an EventLogReader`() {
        runner()
            .withBean(Clock::class.java, { Clock.systemUTC() })
            .withBean(EventCatalogue::class.java, { EventCatalogue(emptyList()) })
            .withBean(TenantScope::class.java, { scope })
            .withBean(OutboxWriter::class.java, { mockk<OutboxWriter>() })
            .withBean(AuditWriter::class.java, { mockk<AuditWriter>() })
            .withBean(EventLogReader::class.java, { mockk<EventLogReader>() })
            .withBean(AuthorityReplica::class.java, { AuthorityReplica(Clock.systemUTC()) })
            .withBean("subscription", ConsumerSubscription::class.java, { subscription() })
            .run { context ->
                context.startupFailure shouldBe null
                context.count(EventLogHead::class.java) shouldBe 0
                context.count(ConsumerRebuildRuntime::class.java) shouldBe 0
                context.count(ReplicaColdStartSync::class.java) shouldBe 0
            }
    }

    @Test
    fun `without a ConsumerSubscription the context starts with the default authorizer and no runtime or sync`() {
        withoutSubscription.run { context ->
            context.startupFailure shouldBe null
            context.count(ConsumerRebuildRuntime::class.java) shouldBe 0
            context.count(ReplicaColdStartSync::class.java) shouldBe 0
            context.count(ConsumerRebuildAuthorizer::class.java) shouldBe 1
        }
    }

    @Test
    fun `one ConsumerSubscription is enough for the runtime and the sync`() {
        base.run { context ->
            context.count(ConsumerRebuildRuntime::class.java) shouldBe 1
            context.count(ReplicaColdStartSync::class.java) shouldBe 1
        }
    }

    @Test
    fun `a service's own authorizer replaces the default`() {
        val own = ConsumerRebuildAuthorizer { true }
        base.withBean("ownAuthorizer", ConsumerRebuildAuthorizer::class.java, { own }).run { context ->
            context.count(ConsumerRebuildAuthorizer::class.java) shouldBe 1
            context.getBean(ConsumerRebuildAuthorizer::class.java) shouldBeSameInstanceAs own
        }
    }

    @Test
    fun `a service's own runtime keeps the field and no second runtime is defined`() {
        val own = mockk<ConsumerRebuildRuntime>()
        base.withBean("ownRuntime", ConsumerRebuildRuntime::class.java, { own }).run { context ->
            context.startupFailure shouldBe null
            context.count(ConsumerRebuildRuntime::class.java) shouldBe 1
            context.getBean(ConsumerRebuildRuntime::class.java) shouldBeSameInstanceAs own
        }
    }

    @Test
    fun `a service's own sync keeps the field and no second sync is defined`() {
        val own = ReplicaColdStartSync(mockk<ConsumerRebuildRuntime>(), AuthorityReplica(Clock.systemUTC()))
        base.withBean("ownSync", ReplicaColdStartSync::class.java, { own }).run { context ->
            context.startupFailure shouldBe null
            context.count(ReplicaColdStartSync::class.java) shouldBe 1
            context.getBean(ReplicaColdStartSync::class.java) shouldBeSameInstanceAs own
        }
    }

    @Test
    fun `two subscriptions with the schemas a and b fail the start with both named`() {
        base
            .withBean("second", ConsumerSubscription::class.java, { subscription("other") })
            .withBean("third", ConsumerSubscription::class.java, { ConsumerSubscription("svc", "gst.x.v1", "a") })
            .run { it.startupFailure.toString() shouldContain "other" }
        withoutSubscription
            .withBean("sa", ConsumerSubscription::class.java, { subscription("a") })
            .withBean("sb", ConsumerSubscription::class.java, { subscription("b") })
            .run { context ->
                val failure = context.startupFailure.toString()
                failure shouldContain "a"
                failure shouldContain "same schema"
                failure shouldContain ", "
                (failure.contains("a, b") || failure.contains("b, a")) shouldBe true
            }
    }

    @Test
    fun `the default authorizer refuses every actor`() {
        base.run { context ->
            val authorizer = context.getBean(ConsumerRebuildAuthorizer::class.java)
            listOf("platform.engineer", "deployment.operator", "system:replica-cold-start", "").forEach {
                authorizer.isPlatformOperator(it) shouldBe false
            }
        }
    }
}
