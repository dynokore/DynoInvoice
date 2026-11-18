package com.dynokore.gst.reconciliation.adapters

import com.dynokore.gst.reconciliation.adapters.persistence.JooqRunAnnotations
import com.dynokore.gst.reconciliation.adapters.persistence.JooqRunAttestations
import com.dynokore.gst.reconciliation.application.workpaper.AnnotateVariance
import com.dynokore.gst.reconciliation.application.workpaper.AnnotationResult
import com.dynokore.gst.reconciliation.application.workpaper.RunAnnotations
import com.dynokore.gst.reconciliation.application.workpaper.RunAttestations
import com.dynokore.gst.reconciliation.application.workpaper.StoredAnnotation
import com.dynokore.gst.reconciliation.contract.model.WorkPaper
import com.dynokore.gst.reconciliation.domain.annotation.AnnotationRefusal
import com.dynokore.gst.reconciliation.domain.boxes.UnmappedLine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.http.HttpStatus
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

/** The annotation store over the session of the current transaction, built per call as the fixture's reader is. */
class LazyRunAnnotations(
    private val dsl: () -> DSLContext,
) : RunAnnotations {
    override fun latest(
        tenant: String,
        runId: String,
        box: String,
    ) = JooqRunAnnotations(dsl()).latest(tenant, runId, box)

    override fun all(
        tenant: String,
        runId: String,
    ) = JooqRunAnnotations(dsl()).all(tenant, runId)

    override fun insert(
        tenant: String,
        stored: StoredAnnotation,
    ) = JooqRunAnnotations(dsl()).insert(tenant, stored)
}

class LazyRunAttestations(
    private val dsl: () -> DSLContext,
) : RunAttestations {
    override fun get(
        tenant: String,
        runId: String,
    ) = JooqRunAttestations(dsl()).get(tenant, runId)
}

/** INV-AC-24379 (INV-T-24380): the annotation and attestation stores and the work-paper read, over PostgreSQL. */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class WorkPaperAnnotationDbTest : RunStoreDbFixture() {
    private val clock = Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC)
    private val annotate =
        AnnotateVariance(
            reader,
            LazyRunAnnotations { session.dsl() },
            LazyRunAttestations { session.dsl() },
            clock,
        )

    private fun unexplainedBox1() =
        outcome(lines = listOf(line(comparison("1", "100.00", "90.00", "10.00"), "10.00")))
            .copy(unmappedLines = listOf(UnmappedLine(UUID.randomUUID().toString(), 1, 3, "XX")))

    private fun stored(tenant: UUID): RunId {
        storeReturn(tenant)
        return RunId(record(tenant, "2026-11-19T09:00:00Z", unexplainedBox1()).runId)
    }

    @JvmInline
    private value class RunId(
        val value: String,
    )

    private fun annotate(
        tenant: UUID,
        run: String,
        box: String,
        cls: String?,
        refs: List<String>,
    ): AnnotationResult =
        asTenant(tenant) {
            session.runner.inTransaction { annotate.execute(tenant.toString(), run, box, cls, refs, USER) }
        }

    private fun rows(run: String) =
        scalar("select count(*) from reconciliation.work_paper_annotation where run_id = '$run'")

    private fun workPaper(
        tenant: UUID,
        run: String,
    ) = asTenant(tenant) { controller.getWorkPaper(UUID.fromString(run)) }

    @Test
    fun `INV-AC-24379 annotations are stored append-only with the acting user and a repeat adds no row`() {
        val t1 = UUID.randomUUID()
        val run = stored(t1).value
        val runRows = scalar("select count(*) from reconciliation.reconciliation_run_box where run_id = '$run'")
        val first = annotate(t1, run, "1", "TIMING_DIFFERENCE", listOf("DOC-1"))
        (first as AnnotationResult.Accepted).stored.annotation.references shouldBe listOf("DOC-1")
        first.stored.annotation.varianceClass
            ?.value shouldBe "TIMING_DIFFERENCE"
        scalar("select annotated_by from reconciliation.work_paper_annotation where run_id = '$run'") shouldBe USER
        scalar("select revision from reconciliation.work_paper_annotation where run_id = '$run'") shouldBe 1
        val again = annotate(t1, run, "1", "TIMING_DIFFERENCE", listOf("DOC-1"))
        (again is AnnotationResult.Replayed) shouldBe true
        (again as AnnotationResult.Replayed).stored shouldBe first.stored
        rows(run) shouldBe 1L
        val changed = annotate(t1, run, "1", "TIMING_DIFFERENCE", listOf("DOC-2"))
        (changed is AnnotationResult.Accepted) shouldBe true
        rows(run) shouldBe 2L
        scalar(
            "select \"references\"::text from reconciliation.work_paper_annotation " +
                "where run_id = '$run' and revision = 1",
        ) shouldBe "{DOC-1}"
        scalar(
            "select count(*) from reconciliation.work_paper_annotation where run_id = '$run' and revision = 2",
        ) shouldBe
            1L
        scalar("select count(*) from reconciliation.reconciliation_run_box where run_id = '$run'") shouldBe runRows
    }

    @Test
    fun `INV-AC-24379 a refused request writes no row and an unknown box or a run of another tenant is not found`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        val run = stored(t1).value
        val buyer = annotate(t1, run, "1", "BUYER_REJECTED", listOf("DOC-1"))
        (buyer as AnnotationResult.Refused).reason shouldBe AnnotationRefusal.CLASS_NOT_ASSIGNABLE
        buyer.reason.code shouldBe "PER-015"
        (annotate(t1, run, "1", "TIMING_DIFFERENCE", emptyList()) as AnnotationResult.Refused).reason shouldBe
            AnnotationRefusal.NO_REFERENCES
        annotate(t1, run, "9", "TIMING_DIFFERENCE", listOf("DOC-1")) shouldBe AnnotationResult.BoxNotFound
        annotate(t2, run, "1", "TIMING_DIFFERENCE", listOf("DOC-1")) shouldBe AnnotationResult.RunNotFound
        rows(run) shouldBe 0L
    }

    @Test
    fun `INV-AC-24379 getWorkPaper returns the latest annotation tenant-isolated and an attested run accepts none`() {
        val t1 = UUID.randomUUID()
        val t2 = UUID.randomUUID()
        val run = stored(t1).value
        workPaper(t1, run).body!!.unexplainedResidual.toPlainString() shouldBe "10.00"
        annotate(t1, run, "1", "TIMING_DIFFERENCE", listOf("DOC-1"))
        annotate(t1, run, "1", "TIMING_DIFFERENCE", listOf("DOC-2"))
        val paper: WorkPaper = workPaper(t1, run).body!!
        paper.unexplainedResidual.toPlainString() shouldBe "0.00"
        paper.boxes
            .single()
            .varianceClass!!
            .value shouldBe "TIMING_DIFFERENCE"
        paper.supportingDocuments shouldBe listOf("DOC-2")
        paper.attestation shouldBe null
        workPaper(t2, run).statusCode shouldBe HttpStatus.NOT_FOUND
        envelope(workPaper(t2, run)).status shouldBe 404

        attest(t1, run)
        val refused = annotate(t1, run, "1", "TIMING_DIFFERENCE", listOf("DOC-3"))
        (refused as AnnotationResult.Refused).reason shouldBe AnnotationRefusal.RUN_ATTESTED
        rows(run) shouldBe 2L
        workPaper(t1, run).body!!.attestation!!.attestedBy shouldBe "user-A"
    }

    @Test
    fun `INV-AC-24379 an update or delete of either table fails with SQLSTATE 42501 and a rollback leaves no row`() {
        val t1 = UUID.randomUUID()
        val run = stored(t1).value
        annotate(t1, run, "1", "TIMING_DIFFERENCE", listOf("DOC-1"))
        attest(t1, run)
        for (table in listOf("work_paper_annotation", "work_paper_attestation")) {
            runtimeSqlState(t1, "update reconciliation.$table set run_id = run_id") shouldBe "42501"
            runtimeSqlState(t1, "delete from reconciliation.$table") shouldBe "42501"
            triggerRefuses("update reconciliation.$table set run_id = run_id")
            triggerRefuses("delete from reconciliation.$table")
        }
        val before = rows(run)
        shouldThrow<IllegalStateException> {
            asTenant(t1) {
                session.runner.inTransaction {
                    annotate.execute(t1.toString(), run, "1", "NO_INVOICE_REQUIRED", listOf("DOC-9"), USER)
                    error("rollback")
                }
            }
        }
        rows(run) shouldBe before
    }

    private fun attest(
        tenant: UUID,
        run: String,
    ) = ReconciliationDb.superuser().use { c ->
        c
            .prepareStatement(
                "insert into reconciliation.work_paper_attestation (tenant_id, run_id, attested_by, attested_at) " +
                    "values (?, ?, 'user-A', '2026-11-19T09:30:00Z')",
            ).use {
                it.setObject(1, tenant)
                it.setString(2, run)
                it.executeUpdate()
            }
    }

    /** The trigger itself refuses, even for a login that holds the privilege. */
    private fun triggerRefuses(sql: String) {
        val failure =
            shouldThrow<SQLException> {
                ReconciliationDb.superuser().use { c -> c.createStatement().use { it.executeUpdate(sql) } }
            }
        failure.sqlState shouldBe "42501"
        failure.message!! shouldContain "append-only"
    }

    @Test
    fun `INV-AC-24379 the other operations of RunsController still answer 501`() {
        val id = UUID.randomUUID()
        controller.exportWorkPaper(id, "PDF").statusCode shouldBe HttpStatus.NOT_IMPLEMENTED
    }
}
