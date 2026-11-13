package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.compliance.ComplianceTenantSource
import com.dynokore.gst.submission.application.queue.TenantTransactions
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock
import java.time.Duration

/** Binding and registration of `inv.backlog-evaluation.*` (INV-AC-20583): fail closed, no default in code. */
class BacklogEvaluationWiringTest {
    private val cron = "inv.backlog-evaluation.cron"
    private val tolerance = "inv.backlog-evaluation.tolerance"
    private val valid = mapOf(cron to "0 */15 * * * *", tolerance to "PT30M")

    private fun bind(values: Map<String, Any>) =
        Binder(MapConfigurationPropertySource(values))
            .bindOrCreate("inv.backlog-evaluation", Bindable.of(BacklogEvaluationProperties::class.java))

    @Test
    fun `INV-AC-20583 a valid cron and tolerance bind`() {
        val bound = bind(valid)
        bound.cron shouldBe "0 */15 * * * *"
        bound.tolerance shouldBe Duration.ofMinutes(30)
    }

    @Test
    fun `INV-AC-20583 a missing cron or tolerance fails the bind`() {
        shouldThrow<Exception> { bind(valid - cron) }
        shouldThrow<Exception> { bind(valid - tolerance) }
        shouldThrow<Exception> { bind(emptyMap()) }
    }

    @Test
    fun `INV-AC-20583 a blank or invalid cron fails the bind`() {
        listOf("", "  ", "every day", "0 0 2 * *", "61 * * * * *").forEach {
            shouldThrow<Exception> { bind(valid + (cron to it)) }
        }
    }

    @Test
    fun `INV-AC-20583 a blank or invalid or zero or negative tolerance fails the bind`() {
        listOf("", "  ", "30 minutes", "30", "PT0S", "PT0M", "-PT1M", "PT-1M").forEach {
            shouldThrow<Exception> { bind(valid + (tolerance to it)) }
        }
    }

    @Test
    fun `INV-AC-20583 the shipped yaml binds`() {
        val source =
            org.springframework.boot.env
                .YamlPropertySourceLoader()
                .load(
                    "application",
                    org.springframework.core.io
                        .ClassPathResource("application.yaml"),
                ).single() as org.springframework.core.env.EnumerablePropertySource<*>
        val shipped =
            source.propertyNames
                .filter { it.startsWith("inv.backlog-evaluation.") }
                .associateWith { source.getProperty(it)!! }
        shipped.keys shouldBe setOf(cron, tolerance)
        bind(shipped).tolerance.isNegative shouldBe false
    }

    @Test
    fun `INV-AC-20583 with no job runtime nothing is registered and the context starts`() {
        val tenants =
            object : TenantContext {
                override fun current(): TenantId = error("unused")

                override fun currentActor(): String = "svc:submission-svc"

                override fun <T> bind(
                    tenant: TenantId,
                    actor: String,
                    block: () -> T,
                ): T = block()
            }
        val runner =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T = block()
            }
        val outbox =
            object : OutboxWriter {
                override fun append(
                    events: List<OutboundEvent>,
                    context: EventContext,
                ): List<Envelope> = emptyList()
            }
        ApplicationContextRunner()
            .withBean(DSLContext::class.java, { DSL.using(SQLDialect.POSTGRES) })
            .withBean(TransactionRunner::class.java, { runner })
            .withBean(OutboxWriter::class.java, { outbox })
            .withBean(ComplianceTenantSource::class.java, { ComplianceTenantSource { emptyList() } })
            .withBean(TenantTransactions::class.java, { TenantTransactions(tenants, runner, WorkloadPrincipal("s")) })
            .withBean(Clock::class.java, { Clock.systemUTC() })
            .withUserConfiguration(BacklogEvaluationConfiguration::class.java)
            .withPropertyValues(*valid.map { "${it.key}=${it.value}" }.toTypedArray())
            .run { context ->
                context.startupFailure shouldBe null
                context.getBeansOfType(JobRuntime::class.java).isEmpty() shouldBe true
                context.containsBean("backlogEvaluationJobRegistration") shouldBe true
            }
        ApplicationContextRunner()
            .withUserConfiguration(BacklogEvaluationConfiguration::class.java)
            .run { (it.startupFailure != null) shouldBe true }
    }
}
