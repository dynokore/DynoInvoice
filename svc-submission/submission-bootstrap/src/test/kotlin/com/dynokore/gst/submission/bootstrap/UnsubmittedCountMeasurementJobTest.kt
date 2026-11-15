package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.application.offboarding.MeasureUnsubmittedCount
import com.dynokore.gst.submission.application.offboarding.MeasuredTenantSource
import com.dynokore.gst.submission.application.offboarding.UnsubmittedCountsByEntity
import com.dynokore.gst.submission.application.queue.TenantTransactions
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
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
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.ClassPathResource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The scheduled measurement job (INV-REQ-956, INV-AC-23028) over fakes: tenant source, counts, outbox, clock. */
class UnsubmittedCountMeasurementJobTest {
    private val t1 = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val t2 = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a2"))
    private val t3 = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a3"))
    private val now = Instant.parse("2026-11-15T08:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    private class Written(
        val tenant: TenantId,
        val type: String,
        val data: String,
        val boundTenant: TenantId?,
    )

    /** One scope: a current-tenant binding, a transaction that commits the buffered events only when it returns. */
    private class Rig {
        var current: TenantId? = null
        var inTransaction = false
        val committed = mutableListOf<Written>()
        private val buffer = mutableListOf<Written>()
        var appendedOutsideTransaction = 0

        val tenants =
            object : TenantContext {
                override fun current(): TenantId = checkNotNull(current)

                override fun currentActor(): String = "svc:submission-svc"

                override fun <T> bind(
                    tenant: TenantId,
                    actor: String,
                    block: () -> T,
                ): T {
                    current = tenant
                    try {
                        return block()
                    } finally {
                        current = null
                    }
                }
            }
        val runner =
            object : TransactionRunner {
                override fun <T> inTransaction(block: () -> T): T {
                    inTransaction = true
                    buffer.clear()
                    try {
                        val result = block()
                        committed += buffer
                        return result
                    } finally {
                        inTransaction = false
                        buffer.clear()
                    }
                }
            }
        val outbox =
            object : OutboxWriter {
                override fun append(
                    events: List<OutboundEvent>,
                    context: EventContext,
                ): List<Envelope> {
                    if (!inTransaction) appendedOutsideTransaction++
                    events.forEach { buffer += Written(context.tenant, it.type, it.dataJson, current) }
                    return emptyList()
                }
            }
        val scope = TenantTransactions(tenants, runner, WorkloadPrincipal("submission-svc"))
    }

    private fun job(
        rig: Rig,
        tenants: List<TenantId>,
        counts: Map<TenantId, Map<String, Int>>,
        failing: Set<TenantId> = emptySet(),
    ) = UnsubmittedCountMeasurementJob(
        MeasuredTenantSource { tenants },
        MeasureUnsubmittedCount(
            UnsubmittedCountsByEntity { tenant ->
                check(tenant !in failing) { "read failed" }
                counts.getValue(tenant)
            },
            clock,
        ),
        rig.scope,
        rig.outbox,
        clock,
    )

    private val counts =
        mapOf(
            t1 to mapOf("E1" to 3, "E2" to 2),
            t2 to mapOf("E3" to 1),
            t3 to emptyMap(),
        )

    private fun expected(
        total: Int,
        entities: String,
    ) = """{"measuredAt":"$now","unsubmittedDocumentCount":$total,"entities":[$entities]}"""

    private val expectedByTenant =
        mapOf(
            t1 to
                expected(
                    5,
                    """{"entityRef":"E1","unsubmittedDocumentCount":3},""" +
                        """{"entityRef":"E2","unsubmittedDocumentCount":2}""",
                ),
            t2 to expected(1, """{"entityRef":"E3","unsubmittedDocumentCount":1}"""),
            t3 to expected(0, ""),
        )

    @Test
    fun `INV-AC-23028 one event per tenant with the type, the topic and the values of that tenant`() {
        val rig = Rig()
        job(rig, listOf(t1, t2, t3), counts).run() shouldBe 3
        rig.committed.map { it.tenant } shouldBe listOf(t1, t2, t3)
        rig.committed.forEach {
            it.type shouldBe "com.dynokore.gst.submission.UnsubmittedDocumentCountMeasured.v1"
            SubmissionEvents.topicByType[it.type] shouldBe "gst.submission.unsubmitted-document-count-measured.v1"
            it.data shouldBe expectedByTenant.getValue(it.tenant)
        }
    }

    @Test
    fun `INV-AC-23028 each event is written inside its own tenant transaction`() {
        val rig = Rig()
        job(rig, listOf(t1, t2, t3), counts).run()
        rig.appendedOutsideTransaction shouldBe 0
        rig.committed.forEach { it.boundTenant shouldBe it.tenant }
    }

    @Test
    fun `INV-AC-23028 a second run writes three more, unchanged values included`() {
        val rig = Rig()
        val job = job(rig, listOf(t1, t2, t3), counts)
        job.run() shouldBe 3
        job.run() shouldBe 3
        rig.committed.size shouldBe 6
        rig.committed.take(3).map { it.data } shouldBe rig.committed.drop(3).map { it.data }
    }

    @Test
    fun `INV-AC-23028 a tenant whose measurement throws writes no event, the others are written, the run throws`() {
        val rig = Rig()
        shouldThrow<IllegalStateException> { job(rig, listOf(t1, t2, t3), counts, failing = setOf(t2)).run() }
        rig.committed.map { it.tenant } shouldBe listOf(t1, t3)
        rig.committed.forEach { it.data shouldBe expectedByTenant.getValue(it.tenant) }
    }

    @Test
    fun `INV-AC-23028 an empty tenant list writes nothing`() {
        val rig = Rig()
        job(rig, emptyList(), counts).run() shouldBe 0
        rig.committed.size shouldBe 0
    }

    @Test
    fun `INV-AC-23028 the job is registered under its name with a tolerance of twice the interval`() {
        val definition = UnsubmittedCountMeasurementJob.definition("0 */5 * * * *", clock)
        definition.name shouldBe "unsubmitted-count-measurement"
        definition.tolerance shouldBe Duration.ofMinutes(10)
    }

    private val cron = "inv.unsubmitted-count.cron"

    private fun bind(values: Map<String, Any>) =
        Binder(MapConfigurationPropertySource(values))
            .bindOrCreate("inv.unsubmitted-count", Bindable.of(UnsubmittedCountProperties::class.java))

    @Test
    fun `INV-AC-23028 a valid cron binds`() {
        bind(mapOf(cron to "0 */5 * * * *")).cron shouldBe "0 */5 * * * *"
    }

    @Test
    fun `INV-AC-23028 a missing cron fails the bind`() {
        shouldThrow<Exception> { bind(emptyMap()) }
    }

    @Test
    fun `INV-AC-23028 an empty or blank cron fails the bind`() {
        listOf("", "  ").forEach { shouldThrow<Exception> { bind(mapOf(cron to it)) } }
    }

    @Test
    fun `INV-AC-23028 an unparsable cron fails the bind`() {
        listOf("every five minutes", "0 */5 * * *", "61 * * * * *").forEach {
            shouldThrow<Exception> { bind(mapOf(cron to it)) }
        }
    }

    private fun context(vararg properties: String): ApplicationContextRunner {
        val rig = Rig()
        val platform =
            object : PlatformTenantBinding {
                override fun <T> bindPlatform(
                    actor: String,
                    block: () -> T,
                ): T = block()
            }
        return ApplicationContextRunner()
            .withBean(DSLContext::class.java, { DSL.using(SQLDialect.POSTGRES) })
            .withBean(TransactionRunner::class.java, { rig.runner })
            .withBean(PlatformTenantBinding::class.java, { platform })
            .withBean(WorkloadPrincipal::class.java, { WorkloadPrincipal("submission-svc") })
            .withBean(OutboxWriter::class.java, { rig.outbox })
            .withBean(TenantTransactions::class.java, { rig.scope })
            .withBean(Clock::class.java, { clock })
            .withUserConfiguration(UnsubmittedCountMeasurementConfiguration::class.java)
            .withPropertyValues(*properties)
    }

    @Test
    fun `INV-AC-23028 the application starts with a valid cron and registers nothing without a job runtime`() {
        context("$cron=0 */5 * * * *").run { ctx ->
            ctx.startupFailure shouldBe null
            ctx.getBeansOfType(JobRuntime::class.java).isEmpty() shouldBe true
            ctx.containsBean("unsubmittedCountMeasurementJobRegistration") shouldBe true
        }
    }

    @Test
    fun `INV-AC-23028 the application start fails with a missing cron`() {
        context().run { (it.startupFailure != null) shouldBe true }
    }

    @Test
    fun `INV-AC-23028 the application start fails with an empty cron`() {
        context("$cron=").run { (it.startupFailure != null) shouldBe true }
    }

    @Test
    fun `INV-AC-23028 the application start fails with an unparsable cron`() {
        context("$cron=every five minutes").run { (it.startupFailure != null) shouldBe true }
    }

    @Test
    fun `INV-AC-23028 the shipped yaml sets the cron to every five minutes`() {
        val source =
            YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml")).single()
                as EnumerablePropertySource<*>
        val shipped =
            source.propertyNames
                .filter { it.startsWith("inv.unsubmitted-count.") }
                .associateWith { source.getProperty(it)!! }
        shipped shouldBe mapOf(cron to "0 */5 * * * *")
        bind(shipped).cron shouldBe "0 */5 * * * *"
    }
}
