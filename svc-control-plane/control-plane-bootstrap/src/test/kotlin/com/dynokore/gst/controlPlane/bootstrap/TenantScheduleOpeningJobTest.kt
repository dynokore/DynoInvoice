package com.dynokore.gst.controlPlane.bootstrap

import com.dynokore.gst.controlPlane.adapters.schedule.ScheduleJobAuditWriter
import com.dynokore.gst.controlPlane.application.schedule.CloseDueTenants
import com.dynokore.gst.controlPlane.application.schedule.OpenDueTenants
import com.dynokore.gst.controlPlane.application.schedule.TenantClosing
import com.dynokore.gst.controlPlane.application.schedule.TenantOpening
import com.dynokore.gst.controlPlane.application.schedule.TenantScheduleRepository
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.schedule.CustodyHolder
import com.dynokore.gst.controlPlane.domain.schedule.ScheduleState
import com.dynokore.gst.controlPlane.domain.schedule.TenantSchedule
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.jobs.DeadlineSource
import com.dynokore.platform.serviceruntime.jobs.JobCommandAuthorizer
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor

/**
 * The job `tenant-schedule-opening` runs the closing step after the opening step (INV-AC-22713, INV-T-22712). The job,
 * the two steps and the audit writer are the real classes; the ports (repository, opener, closer, platform binding,
 * audit sink) are small fakes. The only part taken from the composition is the connectionless [JobRuntime] the job
 * registers with.
 */
class TenantScheduleOpeningJobTest {
    private val offboarding = Instant.parse("2027-12-01T01:00:00Z")
    private val clock: Clock = Clock.fixed(offboarding, java.time.ZoneOffset.UTC)
    private val order = ArrayList<String>()
    private val actors = ArrayList<String>()
    private val auditEntries = ArrayList<AuditEntry>()
    private var openingFails: RuntimeException? = null
    private val tenant = CommercialRef("TEN-1")
    private val schedule =
        TenantSchedule(
            tenant,
            "SCH-1",
            CommercialRef("CTR-1"),
            ScheduleState.APPROVED,
            Instant.parse("2026-12-01T01:00:00Z"),
            offboarding,
            "alice",
            "bob",
            CustodyHolder("Custodian", "c@x.example", null),
            30,
            Instant.parse("2026-12-01T01:00:00Z"),
        )
    private val rows = linkedMapOf(tenant to schedule)

    private val repository =
        object : TenantScheduleRepository {
            override fun find(tenantRef: CommercialRef) = rows[tenantRef]

            override fun insert(
                schedule: TenantSchedule,
                setAt: Instant,
            ) = Unit

            override fun update(schedule: TenantSchedule) {
                rows[schedule.tenantRef] = schedule
            }

            override fun dueForOpening(
                now: Instant,
                limit: Int,
            ): List<CommercialRef> {
                order += "opening"
                openingFails?.let { throw it }
                return emptyList()
            }

            override fun dueForClosing(
                now: Instant,
                limit: Int,
            ) = rows.values
                .filter { it.state == ScheduleState.APPROVED && !it.offboardingAt.isAfter(now) }
                .map { it.tenantRef }

            override fun recordOpenAttempt(
                tenantRef: CommercialRef,
                at: Instant,
            ) = Unit

            override fun lock(tenantRef: CommercialRef) = rows[tenantRef]
        }
    private val transactions =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }
    private val auditWriter =
        object : AuditWriter {
            override fun record(
                entry: AuditEntry,
                context: EventContext,
            ): Envelope {
                auditEntries += entry
                return Envelope(
                    EventId("018f0000-0000-7000-8000-000000000001"),
                    "/gst/control-plane",
                    "com.dynokore.gst.platform.AccreditationAlertRaised.v1",
                    "SCH-1",
                    offboarding,
                    TenantId.PLATFORM,
                    EntityRef.PLATFORM,
                    null,
                    null,
                    1,
                    "i-1",
                    CorrelationId("c"),
                    CausationId("k"),
                    "{}",
                )
            }
        }
    private val audit = ScheduleJobAuditWriter(auditWriter)
    private val platform =
        object : PlatformTenantBinding {
            override fun <T> bindPlatform(
                actor: String,
                block: () -> T,
            ): T {
                actors += actor
                order += "bind:$actor"
                return block()
            }
        }
    private val opening =
        OpenDueTenants(repository, { _, _ -> TenantOpening.OPENED }, audit, transactions, clock)
    private val closing =
        CloseDueTenants(
            repository,
            { _, _ ->
                order += "closeTenant"
                TenantClosing.Closed
            },
            audit,
            transactions,
            clock,
        )

    private fun job(mount: Path): TenantScheduleOpeningJob {
        var job: TenantScheduleOpeningJob? = null
        controlPlaneRunner(mount).run { context ->
            context.startupFailure shouldBe null
            val runtime =
                JobRuntime(
                    SERVICE,
                    "controlplane",
                    context.getBean(TenantScope::class.java),
                    DSL.using(SQLDialect.POSTGRES),
                    context.getBean(OutboxWriter::class.java),
                    context.getBean(AuditWriter::class.java),
                    context.getBean(JobCommandAuthorizer::class.java),
                    context.getBean(DeadlineSource::class.java),
                    Clock.systemUTC(),
                    Executor { it.run() },
                )
            job =
                TenantScheduleOpeningJob(
                    runtime,
                    SERVICE,
                    "0 * * * * *",
                    Duration.ofMinutes(1),
                    TenantScheduleOpeningJob.Collaborators(opening, closing, platform, BATCH),
                )
        }
        return checkNotNull(job)
    }

    @Test
    fun `INV-AC-22713 the job runs the closing step after the opening step in one run`(
        @TempDir mount: Path,
    ) {
        job(mount).runOnce() shouldBe 1
        order shouldBe listOf("bind:$SERVICE", "opening", "closeTenant")
        rows.getValue(tenant).state shouldBe ScheduleState.EXECUTED
    }

    @Test
    fun `INV-AC-22713 the job closes a due schedule although the opening step threw and rethrows the opening failure`(
        @TempDir mount: Path,
    ) {
        val failure = IllegalStateException("database down")
        openingFails = failure
        val thrown = runCatching { job(mount).runOnce() }.exceptionOrNull()
        (thrown === failure) shouldBe true
        order shouldBe listOf("bind:$SERVICE", "opening", "closeTenant")
        order.count { it == "closeTenant" } shouldBe 1
    }

    @Test
    fun `INV-AC-22713 the job runs under the platform binding of its own service name`(
        @TempDir mount: Path,
    ) {
        job(mount).runOnce()
        actors shouldBe listOf(SERVICE)
        auditEntries.single().action shouldBe CloseDueTenants.ACTION
        // the closing's audit entry names the scheduled job, never a person
        auditEntries.single().actor shouldBe OpenDueTenants.ACTOR
    }

    private companion object {
        const val SERVICE = "control-plane-svc"
        const val BATCH = 10
    }
}
