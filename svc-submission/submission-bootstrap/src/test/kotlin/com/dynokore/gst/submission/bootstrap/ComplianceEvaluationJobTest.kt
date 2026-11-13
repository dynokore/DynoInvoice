package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Outbox
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.compliance.AlertEmissionLedger
import com.dynokore.gst.submission.application.compliance.ComplianceMetricsConfig
import com.dynokore.gst.submission.application.compliance.ComplianceTenantSource
import com.dynokore.gst.submission.application.compliance.ObligationRowSource
import com.dynokore.gst.submission.application.queue.TenantTransactions
import com.dynokore.gst.submission.domain.metrics.ObligationRow
import com.dynokore.gst.submission.domain.metrics.Ratio
import com.dynokore.platform.outboxandqueue.EmissionNotPermitted
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.jobs.FireResult
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobTarget
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.context.ApplicationContext
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.core.io.ClassPathResource
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/** The daily compliance evaluation over the Testcontainers database (INV-REQ-1069, INV-AC-2094, INV-AC-12837). */
class ComplianceEvaluationJobTest {
    private val day1 = LocalDate.parse("2026-10-29")
    private val config = ComplianceMetricsConfig(3, Ratio(1, 10), 6)

    private class MutableClock(
        var day: LocalDate,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant(): Instant = day.atStartOfDay().toInstant(ZoneOffset.UTC).plusSeconds(7200)
    }

    private class Fixture(
        val context: ApplicationContext,
        val clock: MutableClock,
        val tenant: UUID = UUID.randomUUID(),
    ) {
        val dsl: DSLContext = context.getBean(DSLContext::class.java)

        fun <T> asTenant(
            id: UUID = tenant,
            block: () -> T,
        ): T =
            context.getBean(TenantContext::class.java).bind(TenantId(id), "svc:submission-svc") {
                context.getBean(TransactionRunner::class.java).inTransaction(block)
            }
    }

    private fun fixture(
        dir: Path,
        block: (Fixture) -> Unit,
    ) {
        SubmissionContext.online(dir).run { context ->
            context.startupFailure shouldBe null
            block(Fixture(context, MutableClock(day1)))
        }
    }

    private fun Fixture.doc(
        tenantId: UUID,
        entity: String,
        due: LocalDate,
        submitted: Boolean,
    ): String {
        val doc = UUID.randomUUID().toString()
        asTenant(tenantId) {
            val e = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
            dsl
                .insertInto(e)
                .set(e.TENANT_ID, tenantId)
                .set(e.DOC_UUID, doc)
                .set(e.ENTITY_REF, entity)
                .set(e.PERIOD_REF, "2026Q1")
                .set(e.DUE_DATE, due)
                .set(e.IN_SCOPE, true)
                .set(e.KIND, "INVOICE")
                .execute()
            if (submitted) markSubmitted(tenantId, doc, entity)
        }
        return doc
    }

    private fun Fixture.markSubmitted(
        tenantId: UUID,
        doc: String,
        entity: String,
    ) {
        val s = Submission.SUBMISSION_
        dsl
            .insertInto(s)
            .set(s.TENANT_ID, tenantId)
            .set(s.DOC_UUID, doc)
            .set(s.VERSION, 1)
            .set(s.STATE, "ACKNOWLEDGED")
            .set(s.ENTITY_REF, entity)
            .execute()
    }

    private fun Fixture.events(tenantId: UUID = tenant): List<String> =
        asTenant(tenantId) {
            dsl
                .select(Outbox.OUTBOX.TYPE, Outbox.OUTBOX.DATA)
                .from(Outbox.OUTBOX)
                .where(Outbox.OUTBOX.TYPE.eq(TYPE))
                .fetch()
                .map { checkNotNull(it.value2()).data() }
        }

    private fun Fixture.job(
        tenants: List<UUID> = listOf(tenant),
        rows: ObligationRowSource = context.getBean(ObligationRowSource::class.java),
        outbox: OutboxWriter = context.getBean(OutboxWriter::class.java),
        ledger: AlertEmissionLedger = context.getBean(AlertEmissionLedger::class.java),
    ) = ComplianceEvaluationJob(
        ComplianceEvaluationPorts(ComplianceTenantSource { tenants.map(::TenantId) }, rows, ledger, outbox),
        context.getBean(TenantTransactions::class.java),
        config,
        clock,
    )

    private fun Fixture.tenEntities(): String {
        var open = ""
        (1..10).forEach { n ->
            val late = n == 1
            val due = day1.plusDays(if (late) 3 else 20)
            val doc = doc(tenant, String.format(Locale.ROOT, "E-%03d", n), due, !late)
            if (late) open = doc
        }
        return open
    }

    @Test
    fun `INV-AC-2094 INV-AC-12837 one of ten entities without a submission writes one event and nine none`(
        @TempDir dir: Path,
    ) = fixture(dir) { f ->
        f.tenEntities()
        f.job().run() shouldBe 1
        val data = f.events().single()
        listOf("\"E-001\"", "\"documentsWithoutSubmission\": 1", "\"documentsPastDue\": 0")
            .plus("\"daysToNearestDeadline\": 3")
            .forEach { (it in data) shouldBe true }
    }

    @Test
    fun `INV-AC-12837 the same run twice on one day writes one event`(
        @TempDir dir: Path,
    ) = fixture(dir) { f ->
        f.tenEntities()
        f.job().run() shouldBe 1
        f.job().run() shouldBe 0
        f.events().size shouldBe 1
    }

    @Test
    fun `INV-AC-12837 a run the next day writes a new event`(
        @TempDir dir: Path,
    ) = fixture(dir) { f ->
        f.tenEntities()
        f.job().run()
        f.clock.day = day1.plusDays(1)
        f.job().run() shouldBe 1
        f.events().size shouldBe 2
        (""""daysToNearestDeadline": 2""" in f.events().last()) shouldBe true
    }

    @Test
    fun `INV-AC-12837 a pair that has gone back to zero writes none`(
        @TempDir dir: Path,
    ) = fixture(dir) { f ->
        val open = f.tenEntities()
        f.job().run()
        f.asTenant { f.markSubmitted(f.tenant, open, "E-001") }
        f.clock.day = day1.plusDays(1)
        f.job().run() shouldBe 0
        f.events().size shouldBe 1
    }

    private class FailingRows(
        private val bad: UUID,
        private val inner: ObligationRowSource,
    ) : ObligationRowSource {
        override fun rows(
            tenant: TenantId,
            entityRef: String?,
            periodRef: String?,
        ): List<ObligationRow> {
            check(tenant.value != bad) { "secret detail" }
            return inner.rows(tenant, entityRef, periodRef)
        }
    }

    private class FailingOutbox(
        private val inner: OutboxWriter,
        private val bad: UUID,
    ) : OutboxWriter {
        override fun append(
            events: List<OutboundEvent>,
            context: EventContext,
        ): List<Envelope> {
            check(context.tenant.value != bad) { "outbox down" }
            return inner.append(events, context)
        }
    }

    @Test
    fun `INV-AC-12837 a tenant whose rows throw leaves the other tenant's event and fails the run`(
        @TempDir dir: Path,
    ) = fixture(dir) { f ->
        val other = UUID.randomUUID()
        f.doc(f.tenant, "E-001", day1.plusDays(3), false)
        f.doc(other, "E-009", day1.plusDays(3), false)
        val rows = FailingRows(other, f.context.getBean(ObligationRowSource::class.java))
        val failure = shouldThrow<IllegalStateException> { f.job(listOf(other, f.tenant), rows).run() }
        (failure.message!!.contains("secret detail")) shouldBe false
        f.events(f.tenant).size shouldBe 1
        f.events(other).size shouldBe 0
    }

    @Test
    fun `INV-AC-12837 the claims of a failed tenant are rolled back and its next run writes the event`(
        @TempDir dir: Path,
    ) = fixture(dir) { f ->
        f.doc(f.tenant, "E-001", day1.plusDays(3), false)
        val real = f.context.getBean(OutboxWriter::class.java)
        shouldThrow<IllegalStateException> { f.job(outbox = FailingOutbox(real, f.tenant)).run() }
        f.events().size shouldBe 0
        f.job().run() shouldBe 1
        f.events().size shouldBe 1
    }

    @Test
    fun `INV-AC-12837 the registered job leaves no meter with an entity or period tag`(
        @TempDir dir: Path,
    ) = fixture(dir) { f ->
        f.tenEntities()
        val runtime = f.context.getBean(JobRuntime::class.java)
        runtime.fire("compliance-evaluation", Instant.parse("2026-10-29T02:00:00Z")) shouldBe
            mapOf(JobTarget.Whole to FireResult.RAN)
        val tags =
            f.context
                .getBean(MeterRegistry::class.java)
                .meters
                .flatMap { m -> m.id.tags.map { it.key } }
                .toSet()
        listOf("entity", "entityRef", "period", "periodRef").forEach { (it in tags) shouldBe false }
    }

    @Test
    fun `INV-AC-12837 the catalogue holds exactly the six pairs and refuses look-alike types`(
        @TempDir dir: Path,
    ) = fixture(dir) { f ->
        SubmissionEvents.topicByType shouldBe
            mapOf(
                "com.dynokore.gst.submission.SubmissionDespatched.v1" to "gst.submission.submission-despatched.v1",
                "com.dynokore.gst.submission.SubmissionAcknowledged.v1" to "gst.submission.submission-acknowledged.v1",
                "com.dynokore.gst.submission.SubmissionFailed.v1" to "gst.submission.submission-failed.v1",
                "com.dynokore.gst.submission.ReadinessChanged.v1" to "gst.submission.readiness-changed.v1",
                TYPE to "gst.submission.compliance-alert-values-measured.v1",
                "com.dynokore.gst.submission.DocumentCodeRaised.v1" to "gst.submission.document-code-raised.v1",
            )
        val writer = f.context.getBean(OutboxWriter::class.java)
        listOf(
            "com.dynokore.gst.submission.ComplianceAlertValuesMeasure.v1",
            "com.dynokore.gst.submission.ComplianceAlertValuesMeasured.v2",
        ).forEach { type ->
            shouldThrow<EmissionNotPermitted> {
                f.asTenant {
                    writer.append(
                        listOf(OutboundEvent(type, "{}")),
                        EventContext(
                            TenantId(f.tenant),
                            com.dynokore.platform.outboxandqueue
                                .EntityRef("E-001"),
                            null,
                            null,
                            "k",
                            null,
                            com.dynokore.platform.outboxandqueue
                                .CorrelationId("c"),
                            com.dynokore.platform.outboxandqueue
                                .CausationId("d"),
                        ),
                    )
                }
            }
        }
    }

    @Test
    fun `INV-AC-12837 application yaml has the one cron key and no submission compliance key`() {
        val source = YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml")).single()
        val names = (source as EnumerablePropertySource<*>).propertyNames
        names.none { it.startsWith("submission.compliance.") } shouldBe true
        names.filter { it.startsWith("inv.compliance-evaluation.") } shouldBe listOf("inv.compliance-evaluation.cron")
        val shipped =
            names
                .filter {
                    it.startsWith(
                        "inv.compliance-evaluation.",
                    )
                }.associateWith { source.getProperty(it)!! }
        bind(shipped).cron shouldBe "0 0 2 * * *"
        shouldThrow<Exception> { bind(mapOf("inv.compliance-evaluation.crons" to "0 0 2 * * *")) }
        shouldThrow<Exception> { bind(mapOf("inv.compliance-evaluation.cron" to "every day")) }
    }

    private fun bind(values: Map<String, Any>) =
        Binder(MapConfigurationPropertySource(values))
            .bindOrCreate("inv.compliance-evaluation", Bindable.of(ComplianceEvaluationProperties::class.java))

    private companion object {
        const val TYPE = "com.dynokore.gst.submission.ComplianceAlertValuesMeasured.v1"
    }
}
