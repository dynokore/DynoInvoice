package com.dynokore.gst.evidence.bootstrap

import com.dynokore.gst.evidence.adapters.EvidenceDb
import com.dynokore.gst.evidence.application.export.ExportClassSource
import com.dynokore.gst.evidence.application.export.ExportClassSources
import com.dynokore.gst.evidence.application.export.ExportJob
import com.dynokore.gst.evidence.application.export.ExportJobs
import com.dynokore.gst.evidence.application.export.ExportPackageStore
import com.dynokore.gst.evidence.application.export.ExportRequest
import com.dynokore.gst.evidence.application.export.ExportState
import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.evidencestore.exportpackage.PackageCandidate
import com.dynokore.platform.evidencestore.exportpackage.PackageContentClass
import com.dynokore.platform.evidencestore.exportpackage.PackageItem
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EnvelopeJson
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.LogRecord
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.PollResult
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EventEntityRef

/**
 * INV-AC-23695 (FR-AH.14): the wire form of the offboarding export request at the service boundary. A TENANT-scope
 * request travels with the real tenant and no `entityref`; the record is built by the production [EnvelopeJson]
 * writer and read by the real inbox consumer over PostgreSQL. A look-alike type is refused (OWASP ASVS V1).
 */
class OffboardingExportRequestedWireTest {
    private val fixed = Instant.parse("2026-11-15T08:30:00Z")
    private val fixedClock: Clock = Clock.fixed(fixed, ZoneOffset.UTC)
    private val json = EnvelopeJson()
    private val configuration = OffboardingExportConfiguration()

    private companion object {
        const val TYPE = "com.dynokore.gst.tenancy.OffboardingExportRequested.v1"
        const val LOOK_ALIKE = "com.dynokore.gst.tenancy.OffboardingExportRequestedX.v1"
    }

    private class Log : EventLogReader {
        private val records = mutableListOf<LogRecord>()

        fun add(envelopeJson: String) {
            records += LogRecord(records.size + 1L, envelopeJson)
        }

        override fun read(
            topic: String,
            afterOffset: Long,
            limit: Int,
        ): List<LogRecord> = records.filter { it.offset > afterOffset }.take(limit)
    }

    private class Boundary(
        val context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val dsl: DSLContext = context.getBean(DSLContext::class.java)
        val outbox: OutboxWriter = context.getBean(OutboxWriter::class.java)
        val log = Log()
        val registered = mutableListOf<ExportState>()
        val consumerName = "offboarding-wire-test-" + UUID.randomUUID()
    }

    private fun run(
        dir: Path,
        block: Boundary.() -> Unit,
    ) {
        EvidenceContext.online(dir).run { context -> Boundary(context).block() }
    }

    private fun sources(tenant: TenantId) =
        ExportClassSources(
            PackageContentClass.entries.associateWith { c ->
                ExportClassSource {
                    listOf(
                        PackageCandidate(
                            tenant,
                            EntityRef("E1"),
                            PeriodRef("2026-01"),
                            PackageItem(c, "${c.directory}-1.txt", "${c.name} 1".toByteArray()),
                        ),
                    )
                }
            },
        )

    private fun Boundary.consumer(tenant: TenantId): JdbcInboxConsumer {
        val real = configuration.offboardingExportJobs(dsl, fixedClock)
        val recording =
            object : ExportJobs by real {
                override fun register(request: ExportRequest): ExportJob =
                    real.register(request).also {
                        registered +=
                            it.state
                    }
            }
        val useCase =
            configuration.exportTenantEvidence(
                sources(tenant),
                recording,
                ExportPackageStore { _, _, _ -> "s3://manifest" },
                context.getBean(com.dynokore.gst.evidence.application.export.ExportChainIntegrity::class.java),
                configuration.offboardingExportResultPublisher(outbox, fixedClock),
                fixedClock,
            )
        return JdbcInboxConsumer(
            ConsumerSubscription(
                consumerName,
                OffboardingExportConfiguration.TOPIC,
                OffboardingExportConfiguration.SUBSCRIPTION.schema,
            ),
            context.getBean(EventCatalogue::class.java),
            log,
            scope,
            dsl,
            configuration.offboardingExportRequestedHandler(useCase),
            RebuildRequiredSink { },
            fixedClock,
        )
    }

    /** [entity] null writes the tenant-wide entity, which the production writer leaves off the wire. */
    private fun request(
        tenant: TenantId,
        eventId: String,
        type: String = TYPE,
        entity: String? = null,
        scope: String = "TENANT",
    ): String {
        val members =
            linkedMapOf(
                "tenantRef" to tenant.toString(),
                "offboardingRef" to "OFF-1",
                "exportRef" to "EXP-1",
                "scope" to scope,
            )
        entity?.let { members["entityRef"] = it }
        members["requestedAt"] = "2026-11-15T08:00:00Z"
        val envelope =
            Envelope(
                id = EventId(eventId),
                source = "/gst/tenancy-svc",
                type = type,
                subject = "OFF-1",
                time = fixed,
                tenant = tenant,
                entity = entity?.let { EventEntityRef(it) } ?: EventEntityRef.TENANT_WIDE,
                docUuid = null,
                transactionDate = null,
                docSequence = null,
                instanceId = "tenancy-svc-0",
                correlation = CorrelationId("corr-1"),
                causation = CausationId("cause-1"),
                dataJson = members.entries.joinToString(",", "{", "}") { "\"${it.key}\":\"${it.value}\"" },
            )
        return json.write(envelope)
    }

    private class JobRow(
        val exportRef: String,
        val scope: String,
        val state: String,
    )

    private fun jobs(tenant: TenantId): List<JobRow> =
        EvidenceDb.superuser().use { c ->
            c
                .prepareStatement(
                    "select export_ref, scope, state from evidence.offboarding_export_job where tenant_id = ?",
                ).use {
                    it.setObject(1, tenant.value)
                    it.executeQuery().use { rs ->
                        generateSequence {
                            if (rs.next()) {
                                JobRow(
                                    rs.getString(1),
                                    rs.getString(2),
                                    rs.getString(3),
                                )
                            } else {
                                null
                            }
                        }.toList()
                    }
                }
        }

    private fun Boundary.deadLetterReasons(): List<String> =
        EvidenceDb.superuser().use { c ->
            c.prepareStatement("select reason from evidence.event_dead_letter where consumer = ?").use {
                it.setString(1, consumerName)
                it.executeQuery().use { rs ->
                    generateSequence { if (rs.next()) rs.getString(1) else null }.toList()
                }
            }
        }

    private val applied = PollResult(applied = 1, ignored = 0, duplicates = 0, stale = 0, deadLettered = 0)

    @Test
    fun `INV-AC-23695 a TENANT-scope request without entityref registers one REQUESTED job of the envelope tenant`(
        @TempDir dir: Path,
    ) = run(dir) {
        val tenant = TenantId(UUID.randomUUID())
        val wire = request(tenant, "ev-a")
        (wire.contains("entityref")) shouldBe false
        log.add(wire)
        consumer(tenant).pollOnce() shouldBe applied
        registered shouldBe listOf(ExportState.REQUESTED)
        val job = jobs(tenant).single()
        job.exportRef shouldBe "EXP-1"
        job.scope shouldBe "TENANT"
        job.state shouldBe "READY" // the run ends as OffboardingExportWiringTest expects
        deadLetterReasons() shouldBe emptyList()
    }

    @Test
    fun `INV-AC-23695 an ENTITY-scope request with entityref registers one job`(
        @TempDir dir: Path,
    ) = run(dir) {
        val tenant = TenantId(UUID.randomUUID())
        val wire = request(tenant, "ev-b", entity = "ENT-1", scope = "ENTITY")
        (wire.contains("\"entityref\":\"ENT-1\"")) shouldBe true
        log.add(wire)
        consumer(tenant).pollOnce() shouldBe applied
        registered shouldBe listOf(ExportState.REQUESTED)
        val job = jobs(tenant).single()
        job.exportRef shouldBe "EXP-1"
        job.scope shouldBe "ENTITY"
    }

    @Test
    fun `INV-AC-23695 a look-alike type without entityref is refused with entityref is required and no job exists`(
        @TempDir dir: Path,
    ) = run(dir) {
        val tenant = TenantId(UUID.randomUUID())
        // the writer refuses the tenant-wide entity for this type, so the entityref is cut from the wire text
        val wire =
            request(
                tenant,
                "ev-c",
                type = LOOK_ALIKE,
                entity = "ENT-1",
            ).replace(Regex(""",\"entityref\":\"[^\"]*\""""), "")
        (wire.contains("entityref")) shouldBe false
        log.add(wire)
        consumer(tenant).pollOnce() shouldBe
            PollResult(applied = 0, ignored = 0, duplicates = 0, stale = 0, deadLettered = 1)
        deadLetterReasons() shouldBe listOf("entityref is required")
        jobs(tenant) shouldBe emptyList()
        registered shouldBe emptyList()
    }

    @Test
    fun `INV-AC-23695 the same record id consumed twice registers one job`(
        @TempDir dir: Path,
    ) = run(dir) {
        val tenant = TenantId(UUID.randomUUID())
        val consumer = consumer(tenant)
        log.add(request(tenant, "ev-d"))
        consumer.pollOnce() shouldBe applied
        log.add(request(tenant, "ev-d"))
        consumer.pollOnce() shouldBe PollResult(applied = 0, ignored = 0, duplicates = 1, stale = 0, deadLettered = 0)
        jobs(tenant).size shouldBe 1
        registered.size shouldBe 1
    }
}
