package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcReplayIntake
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.ServiceRuntimeConfiguration
import com.dynokore.platform.serviceruntime.datasource.withBindingKey
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.SqlErrors
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock

/**
 * INV-T-16938 (INV-Q-16934 A, P109): the default `inboxConsumer` of the L1 composition backs off for every
 * [InboxConsumer] except a [JdbcReplayIntake], which reads the replay topic and is no live consumer.
 */
class InboxConsumerDefaultTest {
    private val runnerStub =
        mockk<TransactionRunner>(moreInterfaces = arrayOf(SqlErrors::class, CurrentConnection::class))
    private val scope = TenantScope(mockk<TenantContext>(), runnerStub, runnerStub as CurrentConnection, "svc-workload")

    /** A look-alike: an [InboxConsumer] that is neither a [JdbcInboxConsumer] nor a [JdbcReplayIntake]. */
    private class StubConsumer : InboxConsumer {
        override fun pollOnce(): PollResult = mockk()
    }

    /** The L1 composition with every input of the default consumer present and no [InboxConsumer] bean. */
    private val base =
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
            ).withBean(Clock::class.java, { Clock.systemUTC() })
            .withBean(EventCatalogue::class.java, { EventCatalogue(emptyList()) })
            .withBean(TenantScope::class.java, { scope })
            .withBean(OutboxWriter::class.java, { mockk<OutboxWriter>() })
            .withBean(AuditWriter::class.java, { mockk<AuditWriter>() })
            .withBean(
                "subscription",
                ConsumerSubscription::class.java,
                { ConsumerSubscription("svc", "gst.t.v1", "svc") },
            ).withBean(EventHandler::class.java, { mockk<EventHandler>() })
            .withBean(RebuildRequiredSink::class.java, { mockk<RebuildRequiredSink>() })
            .withBean(EventLogReader::class.java, { mockk<EventLogReader>() })

    @Test
    fun `with all inputs and no InboxConsumer the default consumer is the only JdbcInboxConsumer`() {
        base.run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(JdbcInboxConsumer::class.java).keys shouldBe setOf("inboxConsumer")
        }
    }

    @Test
    fun `a replay intake registered as service configuration does not stop the default consumer`() {
        base
            .withBean("authorityReplayIntake", JdbcReplayIntake::class.java, { mockk<JdbcReplayIntake>() })
            .run { context ->
                context.startupFailure shouldBe null
                context.getBeansOfType(JdbcInboxConsumer::class.java).keys shouldBe setOf("inboxConsumer")
                context.getBeansOfType(JdbcReplayIntake::class.java).keys shouldBe setOf("authorityReplayIntake")
            }
    }

    @Test
    fun `a service-declared JdbcInboxConsumer replaces the default`() {
        base.withBean("ownConsumer", JdbcInboxConsumer::class.java, { mockk<JdbcInboxConsumer>() }).run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(JdbcInboxConsumer::class.java).keys shouldBe setOf("ownConsumer")
        }
    }

    @Test
    fun `a service-declared InboxConsumer of another class makes the default back off`() {
        base.withBean("ownStub", StubConsumer::class.java, { StubConsumer() }).run { context ->
            context.startupFailure shouldBe null
            context.getBeansOfType(JdbcInboxConsumer::class.java).keys shouldBe emptySet()
            context.getBeansOfType(StubConsumer::class.java).keys shouldBe setOf("ownStub")
        }
    }
}
