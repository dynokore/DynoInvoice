package com.dynokore.gst.reconciliation.adapters

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.reconciliation.adapters.http.PendingRetryAfter
import com.dynokore.gst.reconciliation.adapters.http.ReconciliationController
import com.dynokore.gst.reconciliation.adapters.persistence.JooqReturnRecorded
import com.dynokore.gst.reconciliation.application.run.GetReconciliation
import com.dynokore.gst.reconciliation.application.run.ReturnRecorded
import com.dynokore.gst.reconciliation.application.workpaper.AnnotateVariance
import com.dynokore.gst.reconciliation.contract.model.AnnotateVarianceRequest
import com.dynokore.gst.reconciliation.contract.model.VarianceAnnotation
import com.dynokore.gst.reconciliation.domain.boxes.UnmappedLine
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.http.ResponseEntity
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit

/** INV-AC-24379 (INV-T-24380): the endpoints `getWorkPaper` and `annotateVariance` as production wires them. */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class AnnotateVarianceEndpointTest : RunStoreDbFixture() {
    private val clock = Clock.fixed(Instant.parse("2026-11-19T10:00:00Z"), ZoneOffset.UTC)
    private val reconciliation =
        ReconciliationController(
            GetReconciliation(reader, ReturnRecorded { t, e, p -> JooqReturnRecorded(session.dsl()).exists(t, e, p) }),
            session.runner,
            session.tenants,
            PendingRetryAfter(1),
            AnnotateVariance(
                reader,
                LazyRunAnnotations { session.dsl() },
                LazyRunAttestations { session.dsl() },
                clock,
            ),
        )

    /** Box 1: variance 10.00, residual 10.00. Box 2: variance 10.00, TIMING 6.00, NO_INVOICE_REQUIRED 3.00. */
    private fun seed(tenant: UUID): String {
        storeReturn(tenant)
        val lines =
            listOf(
                line(comparison("1", "100.00", "90.00", "10.00"), "10.00"),
                line(
                    comparison("2", "100.00", "90.00", "10.00"),
                    "1.00",
                    Triple("TIMING_DIFFERENCE", "6.00", emptyList()),
                    Triple("NO_INVOICE_REQUIRED", "3.00", emptyList()),
                ),
            )
        val completed =
            outcome(lines = lines).copy(unmappedLines = listOf(UnmappedLine(UUID.randomUUID().toString(), 1, 3, "XX")))
        return record(tenant, "2026-11-19T09:00:00Z", completed).runId
    }

    private fun put(
        tenant: UUID,
        run: String,
        box: String,
        cls: String?,
        refs: List<String>,
    ): ResponseEntity<VarianceAnnotation> =
        asTenant(tenant) {
            reconciliation.annotateVariance(UUID.fromString(run), box, AnnotateVarianceRequest(refs, cls))
        }

    private fun rows(run: String) =
        scalar("select count(*) from reconciliation.work_paper_annotation where run_id = '$run'")

    @Test
    fun `INV-AC-24379 annotateVariance answers 200, a repeat adds no row, new references add a revision`() {
        val t1 = UUID.randomUUID()
        val run = seed(t1)
        val first = put(t1, run, "1", "TIMING_DIFFERENCE", listOf("DOC-1"))
        first.statusCode.value() shouldBe 200
        val body = first.body!!
        body.runId shouldBe UUID.fromString(run)
        body.box shouldBe "1"
        body.varianceClass shouldBe "TIMING_DIFFERENCE"
        body.references shouldBe listOf("DOC-1")
        scalar("select annotated_by from reconciliation.work_paper_annotation where run_id = '$run'") shouldBe USER
        scalar("select revision from reconciliation.work_paper_annotation where run_id = '$run'") shouldBe 1
        val repeat = put(t1, run, "1", "TIMING_DIFFERENCE", listOf("DOC-1"))
        repeat.statusCode.value() shouldBe 200
        repeat.body shouldBe body
        rows(run) shouldBe 1L
        put(t1, run, "1", "TIMING_DIFFERENCE", listOf("DOC-2")).statusCode.value() shouldBe 200
        rows(run) shouldBe 2L
        scalar(
            "select count(*) from reconciliation.work_paper_annotation where run_id = '$run' and revision = 1",
        ) shouldBe
            1L
    }

    @Test
    fun `INV-AC-24379 annotateVariance answers 422 PER-015, 400, 404 and 404 for another tenant and writes no row`() {
        val t1 = UUID.randomUUID()
        val run = seed(t1)
        val buyer = put(t1, run, "1", "BUYER_REJECTED", listOf("DOC-1"))
        buyer.statusCode.value() shouldBe 422
        envelope(buyer).code shouldBe "PER-015"
        val empty = put(t1, run, "1", "TIMING_DIFFERENCE", emptyList())
        empty.statusCode.value() shouldBe 400
        envelope(empty).status shouldBe 400
        put(t1, run, "9", "TIMING_DIFFERENCE", listOf("DOC-1")).statusCode.value() shouldBe 404
        val other = put(UUID.randomUUID(), run, "1", "TIMING_DIFFERENCE", listOf("DOC-1"))
        other.statusCode.value() shouldBe 404
        envelope(other).status shouldBe 404
        rows(run) shouldBe 0L
    }

    @Test
    fun `INV-AC-24379 getWorkPaper returns every class of a Box, the latest annotation, and 404 for another tenant`() {
        val t1 = UUID.randomUUID()
        val run = seed(t1)
        val runRows = scalar("select count(*) from reconciliation.reconciliation_run_variance where run_id = '$run'")
        put(t1, run, "1", "TIMING_DIFFERENCE", listOf("DOC-1"))
        val paper = asTenant(t1) { controller.getWorkPaper(UUID.fromString(run)) }.body!!

        fun shares(box: String) =
            paper.boxes
                .single { it.box == box }
                .varianceClasses
                .map { it.varianceClass.value to it.amount.toPlainString() }
        shares("1") shouldBe listOf("TIMING_DIFFERENCE" to "10.00")
        shares("2") shouldBe
            listOf("NO_INVOICE_REQUIRED" to "3.00", "TIMING_DIFFERENCE" to "6.00", "UNEXPLAINED" to "1.00")
        paper.supportingDocuments shouldBe listOf("DOC-1")
        paper.unexplainedResidual.toPlainString() shouldBe "1.00"
        scalar("select count(*) from reconciliation.reconciliation_run_variance where run_id = '$run'") shouldBe runRows
        val other = asTenant(UUID.randomUUID()) { controller.getWorkPaper(UUID.fromString(run)) }
        other.statusCode.value() shouldBe 404
        envelope(other).status shouldBe 404
    }

    @Test
    fun `INV-AC-24379 an attested run answers 409 PER-019, adds no row, getWorkPaper returns attestation`() {
        val t1 = UUID.randomUUID()
        val run = seed(t1)
        put(t1, run, "1", "TIMING_DIFFERENCE", listOf("DOC-1")).statusCode.value() shouldBe 200
        ReconciliationDb.superuser().use { c ->
            c
                .prepareStatement(
                    "insert into reconciliation.work_paper_attestation (tenant_id, run_id, attested_by, attested_at) " +
                        "values (?, ?, ?, '2026-11-19T09:30:00Z')",
                ).use {
                    it.setObject(1, t1)
                    it.setString(2, run)
                    it.setString(3, USER)
                    it.executeUpdate()
                }
        }
        val refused = put(t1, run, "1", "TIMING_DIFFERENCE", listOf("DOC-3"))
        refused.statusCode.value() shouldBe 409
        envelope(refused).code shouldBe ErrorCode.PER_019.code
        envelope(refused).code shouldNotBe "SRT-008"
        envelope(refused).code shouldNotBe ErrorCode.PER_018.code
        envelope(refused).status shouldBe 409
        rows(run) shouldBe 1L
        val paper = asTenant(t1) { controller.getWorkPaper(UUID.fromString(run)) }.body!!
        paper.attestation!!.attestedBy shouldBe USER
    }

    @Test
    fun `INV-AC-24379 RunsController has no annotateVariance`() {
        com.dynokore.gst.reconciliation.adapters.http.RunsController::class.java.declaredMethods
            .none { it.name == "annotateVariance" } shouldBe true
    }
}
