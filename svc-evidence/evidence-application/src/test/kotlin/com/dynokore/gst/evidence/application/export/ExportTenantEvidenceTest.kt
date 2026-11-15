package com.dynokore.gst.evidence.application.export

import com.dynokore.platform.evidencestore.EntityRef
import com.dynokore.platform.evidencestore.PeriodRef
import com.dynokore.platform.evidencestore.exportpackage.PackageCandidate
import com.dynokore.platform.evidencestore.exportpackage.PackageContentClass
import com.dynokore.platform.evidencestore.exportpackage.PackageFiles
import com.dynokore.platform.evidencestore.exportpackage.PackageItem
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ExportTenantEvidenceTest {
    private val t1 = TenantId(UUID.fromString("abcdef01-2345-6789-abcd-ef0123456789"))
    private val t2 = TenantId(UUID.fromString("abcdef01-2345-6789-abcd-ef0123456790"))
    private val clock = Clock.fixed(Instant.parse("2026-11-15T01:02:03Z"), ZoneOffset.UTC)
    private val request = ExportRequest(t1, "off-1", "exp-1", ExportScope.TENANT, null, "ev-1")
    private val sourced =
        listOf(
            PackageContentClass.TRANSMITTED_XML,
            PackageContentClass.ACKNOWLEDGEMENTS,
            PackageContentClass.DECISION_TRACES,
            PackageContentClass.AUDIT_LOG,
        )

    private fun candidate(
        cls: PackageContentClass,
        n: Int,
        tenant: TenantId = t1,
    ) = PackageCandidate(
        tenant,
        EntityRef("E1"),
        PeriodRef("2026-01"),
        PackageItem(cls, "${cls.directory}-$n.txt", "$cls $n".toByteArray()),
    )

    private fun twoItems(cls: PackageContentClass) = ExportClassSource { listOf(candidate(cls, 1), candidate(cls, 2)) }

    private class FakeJobs(
        var job: ExportJob?,
    ) : ExportJobs {
        var changes = 0

        override fun find(
            tenant: TenantId,
            offboardingRef: String,
        ) = job

        override fun findLatest(
            tenant: TenantId,
            scope: ExportScope,
        ) = job

        override fun register(request: ExportRequest) =
            ExportJob(
                request.tenant,
                request.offboardingRef,
                request.exportRef,
                ExportState.REQUESTED,
            ).also { job = it }

        override fun markReady(
            job: ExportJob,
            manifestRef: String,
            itemCount: Int,
            contents: List<String>,
        ) = job
            .copy(state = ExportState.READY, manifestRef = manifestRef, itemCount = itemCount, contents = contents)
            .also {
                this.job = it
                changes++
            }

        override fun markFailed(
            job: ExportJob,
            reason: String,
        ) = job.copy(state = ExportState.FAILED, failureReason = reason).also {
            this.job = it
            changes++
        }
    }

    private class Rig(
        val sources: Map<PackageContentClass, ExportClassSource?>,
        val allows: Boolean = true,
        val job: ExportJob? = null,
        val clock: Clock,
    ) {
        val jobs = FakeJobs(job)
        val stored = mutableListOf<PackageFiles>()
        val published = mutableListOf<ExportResult>()
        val useCase =
            ExportTenantEvidence(
                ExportClassSources(sources),
                jobs,
                { _, _, files ->
                    stored += files
                    "manifest-ref-1"
                },
                { allows },
                { published += it },
                clock,
            )
    }

    private fun rig(
        extra: Map<PackageContentClass, ExportClassSource?>,
        allows: Boolean = true,
        job: ExportJob? = null,
    ) = Rig(sourced.associateWith { twoItems(it) } + extra, allows, job, clock)

    private val both =
        mapOf(
            PackageContentClass.RECONCILIATION_WORK_PAPERS to twoItems(PackageContentClass.RECONCILIATION_WORK_PAPERS),
            PackageContentClass.SOURCE_DOCUMENT_REFERENCES to twoItems(PackageContentClass.SOURCE_DOCUMENT_REFERENCES),
        )

    private fun onlyOneFailed(r: Rig): ExportResult {
        r.published.size shouldBe 1
        r.published[0].state shouldBe ExportState.FAILED
        return r.published[0]
    }

    @Test
    fun `INV-AC-22946 six sources end READY with one store call`() {
        val r = rig(both)
        val job = r.useCase.execute(request)
        job.state shouldBe ExportState.READY
        r.stored.size shouldBe 1
        job.manifestRef shouldBe "manifest-ref-1"
        job.itemCount shouldBe 12
        r.published.size shouldBe 1
        r.published[0].state shouldBe ExportState.READY
        r.published[0].contents shouldBe PackageContentClass.entries.map { it.name }
        r.published[0].failureReason shouldBe null
    }

    @Test
    fun `INV-AC-22946 two unbound sources fail naming both classes in enum order`() {
        val r = rig(emptyMap())
        val job = r.useCase.execute(request)
        job.state shouldBe ExportState.FAILED
        job.failureReason shouldBe "no source for RECONCILIATION_WORK_PAPERS, SOURCE_DOCUMENT_REFERENCES"
        r.stored.size shouldBe 0
        onlyOneFailed(r).failureReason shouldBe job.failureReason
    }

    @Test
    fun `INV-AC-22946 one unbound source is named alone`() {
        val r =
            rig(
                mapOf(
                    PackageContentClass.RECONCILIATION_WORK_PAPERS to null,
                    PackageContentClass.SOURCE_DOCUMENT_REFERENCES to
                        twoItems(PackageContentClass.SOURCE_DOCUMENT_REFERENCES),
                ),
            )
        val job = r.useCase.execute(request)
        job.failureReason shouldBe "no source for RECONCILIATION_WORK_PAPERS"
        r.stored.size shouldBe 0
        onlyOneFailed(r)
    }

    @Test
    fun `INV-AC-22946 integrity refusing fails naming DEP-007`() {
        val r = rig(both, allows = false)
        val job = r.useCase.execute(request)
        job.state shouldBe ExportState.FAILED
        job.failureReason!! shouldContain "DEP-007"
        r.stored.size shouldBe 0
        onlyOneFailed(r)
    }

    @Test
    fun `INV-AC-22946 foreign tenant candidate fails with the assembly refusal`() {
        val foreign = ExportClassSource { listOf(candidate(PackageContentClass.AUDIT_LOG, 9, t2)) }
        val r = rig(both + (PackageContentClass.AUDIT_LOG to foreign))
        val job = r.useCase.execute(request)
        job.state shouldBe ExportState.FAILED
        job.failureReason shouldBe "assembly refused: FOREIGN_TENANT audit-log-9.txt"
        r.stored.size shouldBe 0
        onlyOneFailed(r)
    }

    @Test
    fun `INV-AC-22946 throwing source fails with at most 500 characters and no rethrow`() {
        val boom = ExportClassSource { throw IllegalStateException("x".repeat(2000)) }
        val r = rig(both + (PackageContentClass.DECISION_TRACES to boom))
        val job = r.useCase.execute(request)
        job.state shouldBe ExportState.FAILED
        job.failureReason shouldBe "java.lang.IllegalStateException"
        (job.failureReason!!.length <= 500) shouldBe true
        r.stored.size shouldBe 0
        onlyOneFailed(r)
    }

    @Test
    fun `INV-AC-22946 second run of a READY job changes and publishes nothing`() {
        val r = rig(both)
        val first = r.useCase.execute(request)
        val second = r.useCase.execute(request)
        second shouldBe first
        r.stored.size shouldBe 1
        r.published.size shouldBe 1
        r.jobs.changes shouldBe 1
    }

    @Test
    fun `INV-AC-22946 a FAILED job is returned unchanged and published once`() {
        val r = rig(emptyMap())
        val first = r.useCase.execute(request)
        val second = r.useCase.execute(request)
        second shouldBe first
        r.published.size shouldBe 1
        r.jobs.changes shouldBe 1
    }

    @Test
    fun `INV-AC-23074 an ENTITY request fails with the named reason, no store call and one FAILED result`() {
        val r = rig(both)
        val job =
            r.useCase.execute(ExportRequest(t1, "off-2", "exp-2", ExportScope.ENTITY, "E1", "ev-2"))
        job.state shouldBe ExportState.FAILED
        job.failureReason shouldBe "scope ENTITY is not supported"
        r.stored.size shouldBe 0
        onlyOneFailed(r).failureReason shouldBe "scope ENTITY is not supported"
    }
}
