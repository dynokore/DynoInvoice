package com.dynokore.gst.reconciliation.application.workpaper

import com.dynokore.gst.reconciliation.application.run.RecordedRun
import com.dynokore.gst.reconciliation.application.run.RunBox
import com.dynokore.gst.reconciliation.application.run.RunCursor
import com.dynokore.gst.reconciliation.application.run.RunReader
import com.dynokore.gst.reconciliation.application.run.RunSnapshot
import com.dynokore.gst.reconciliation.application.run.RunVarianceShare
import com.dynokore.gst.reconciliation.domain.annotation.Annotation
import com.dynokore.gst.reconciliation.domain.annotation.AnnotationRefusal
import com.dynokore.gst.reconciliation.domain.variance.VarianceClass
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WorkPaperReadsTest {
    private val now = Instant.parse("2026-05-01T08:00:00Z")
    private val completed = Instant.parse("2026-04-02T00:00:00Z")

    private fun box(
        id: String,
        submitted: String,
        filed: String,
        variance: String,
        residual: String,
    ) = RunBox(id, BigDecimal(submitted), BigDecimal(filed), BigDecimal(variance), emptyList(), BigDecimal(residual))

    private fun share(
        cls: String,
        amount: String,
    ) = RunVarianceShare(cls, BigDecimal(amount))

    private val run = RecordedRun("R1", "E1", "P1", 2, completed, "F5")
    private val box1 =
        box("1", "100.00", "90.00", "10.00", "10.00")
            .copy(varianceClasses = listOf(share("UNEXPLAINED", "10.00")))
    private val box2 = box("2", "5.00", "5.00", "0.00", "0.00")
    private val snapshot = RunSnapshot(run, listOf(box1, box2), BigDecimal("10.00"), emptyList(), true)

    private val varianceRun = RecordedRun("R-vc", "E1", "P1", 2, completed, "F5")
    private val varianceSnapshot =
        RunSnapshot(
            varianceRun,
            listOf(
                box("1", "100.00", "90.00", "10.00", "4.00").copy(
                    varianceClasses = listOf(share("TIMING_DIFFERENCE", "6.00"), share("UNEXPLAINED", "4.00")),
                ),
                box("2", "9.00", "0.00", "9.00", "0.00").copy(
                    varianceClasses = listOf(share("NO_INVOICE_REQUIRED", "3.00"), share("TIMING_DIFFERENCE", "6.00")),
                ),
            ),
            BigDecimal("4.00"),
            emptyList(),
            true,
        )

    private fun classesOf(
        box: Int,
        cls: String? = null,
    ): List<WorkPaperVarianceShare> {
        if (cls != null) annotate.execute("T1", "R-vc", "1", cls, listOf("DOC-1"), "U1")
        return get.execute("T1", "R-vc")!!.boxes[box].varianceClasses
    }

    private fun vs(
        cls: String,
        amount: String,
    ) = WorkPaperVarianceShare(cls, amount)

    @Test
    fun `INV-AC-24661 boxes without annotation carry every class in order and the header carries the run form`() {
        classesOf(0) shouldBe listOf(vs("TIMING_DIFFERENCE", "6.00"), vs("UNEXPLAINED", "4.00"))
        classesOf(1) shouldBe listOf(vs("NO_INVOICE_REQUIRED", "3.00"), vs("TIMING_DIFFERENCE", "6.00"))
        get.execute("T1", "R-vc")!!.header.returnForm shouldBe varianceRun.returnForm
        get.execute("T1", "R-vc")!!.boxes[0].unexplainedResidual shouldBe "4.00"
    }

    @Test
    fun `INV-AC-24661 an annotation to another class replaces UNEXPLAINED and zeroes the residual`() {
        classesOf(0, "OUTSIDE_BY_RULE") shouldBe listOf(vs("OUTSIDE_BY_RULE", "4.00"), vs("TIMING_DIFFERENCE", "6.00"))
        get.execute("T1", "R-vc")!!.boxes[0].unexplainedResidual shouldBe "0.00"
        classesOf(1) shouldBe listOf(vs("NO_INVOICE_REQUIRED", "3.00"), vs("TIMING_DIFFERENCE", "6.00"))
    }

    @Test
    fun `INV-AC-24661 an annotation to an existing class sums into one member`() {
        classesOf(0, "TIMING_DIFFERENCE") shouldBe listOf(vs("TIMING_DIFFERENCE", "10.00"))
        get.execute("T1", "R-vc")!!.boxes[0].unexplainedResidual shouldBe "0.00"
    }

    private val reader =
        object : RunReader {
            override fun latest(
                tenant: String,
                entityRef: String,
                periodRef: String,
            ): RunSnapshot? = null

            override fun page(
                tenant: String,
                entityRef: String,
                periodRef: String,
                limit: Int,
                after: RunCursor?,
            ): List<RecordedRun> = emptyList()

            override fun find(
                tenant: String,
                runId: String,
            ): RunSnapshot? =
                when {
                    tenant != "T1" -> null
                    runId == "R1" -> snapshot
                    runId == "R-vc" -> varianceSnapshot
                    else -> null
                }
        }

    private class Annotations : RunAnnotations {
        val rows = mutableListOf<Pair<String, StoredAnnotation>>()
        var losses = 0

        override fun latest(
            tenant: String,
            runId: String,
            box: String,
        ) = all(tenant, runId).firstOrNull { it.annotation.box == box }

        override fun all(
            tenant: String,
            runId: String,
        ) = rows
            .filter { it.first == tenant && it.second.runId == runId }
            .map { it.second }
            .reversed()
            .distinctBy { it.annotation.box }

        override fun insert(
            tenant: String,
            stored: StoredAnnotation,
        ): Boolean {
            if (losses > 0) {
                losses--
                return false
            }
            rows += tenant to stored
            return true
        }
    }

    private val store = Annotations()
    private var attestation: StoredAttestation? = null
    private val attestations =
        RunAttestations { tenant, runId -> if (tenant == "T1" && runId == "R1") attestation else null }
    private val annotate = AnnotateVariance(reader, store, attestations, Clock.fixed(now, ZoneOffset.UTC))
    private val get = GetWorkPaper(reader, store, attestations)

    private fun stored() =
        StoredAnnotation("R1", Annotation("1", VarianceClass.TIMING_DIFFERENCE, listOf("DOC-1")), "U1", now)

    private fun go(
        cls: String? = "TIMING_DIFFERENCE",
        refs: List<String> = listOf("DOC-1"),
        box: String = "1",
        tenant: String = "T1",
        actor: String = "U1",
    ) = annotate.execute(tenant, "R1", box, cls, refs, actor)

    @Test
    fun `INV-AC-24377 accepted is stored once with actor and clock, a repeat is replayed without a second insert`() {
        val first = go()
        val expected = stored()
        first shouldBe AnnotationResult.Accepted(expected)
        store.rows.size shouldBe 1
        go() shouldBe AnnotationResult.Replayed(expected)
        store.rows.size shouldBe 1
    }

    @Test
    fun `INV-AC-24377 unassignable class and no references are refused and nothing is stored`() {
        go(cls = "BUYER_REJECTED") shouldBe AnnotationResult.Refused(AnnotationRefusal.CLASS_NOT_ASSIGNABLE)
        AnnotationRefusal.CLASS_NOT_ASSIGNABLE.code shouldBe "PER-015"
        go(refs = emptyList()) shouldBe AnnotationResult.Refused(AnnotationRefusal.NO_REFERENCES)
        store.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-24377 unknown box and another tenant's run are not found and nothing is stored`() {
        go(box = "9") shouldBe AnnotationResult.BoxNotFound
        go(tenant = "T2") shouldBe AnnotationResult.RunNotFound
        store.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-24377 an attested run is refused and nothing is stored`() {
        attestation = StoredAttestation("U9", now)
        go() shouldBe AnnotationResult.Refused(AnnotationRefusal.RUN_ATTESTED)
        store.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-24377 a blank actor is rejected`() {
        shouldThrow<IllegalArgumentException> { go(actor = " ") }
        store.rows.size shouldBe 0
    }

    @Test
    fun `INV-AC-24377 a lost insert re-reads once and decides again, a second loss is a conflict`() {
        store.losses = 1
        go() shouldBe AnnotationResult.Accepted(stored())
        store.rows.size shouldBe 1
        store.losses = 2
        shouldThrow<IllegalStateException> { go(refs = listOf("DOC-2")) }
        store.rows.size shouldBe 1
    }

    @Test
    fun `INV-AC-24377 the work paper shows the annotated residual as classified and the header, before and after`() {
        val before = get.execute("T1", "R1")!!
        before.unexplainedResidual shouldBe "10.00"
        before.supportingDocuments shouldBe emptyList()
        go()
        val view = get.execute("T1", "R1")!!
        view.header shouldBe WorkPaperHeader("E1", "P1", completed, 2, "F5")
        view.boxes[0].varianceClasses shouldBe listOf(WorkPaperVarianceShare("TIMING_DIFFERENCE", "10.00"))
        view.boxes[0].unexplainedResidual shouldBe "0.00"
        view.boxes[1].varianceClasses shouldBe emptyList()
        view.boxes[1].unexplainedResidual shouldBe "0.00"
        view.unexplainedResidual shouldBe "0.00"
        view.supportingDocuments shouldBe listOf("DOC-1")
        view.attestation shouldBe null
        snapshot.boxes[0].unexplainedResidual shouldBe BigDecimal("10.00")
        get.execute("T2", "R1") shouldBe null
        attestation = StoredAttestation("U9", now)
        get.execute("T1", "R1")!!.attestation shouldBe attestation
    }

    @Test
    fun `INV-AC-24377 a class-less annotation adds only references and keeps the class and residual`() {
        go(cls = null, refs = listOf("B-2", "A-1", "B-2"))
        val view = get.execute("T1", "R1")!!
        view.boxes[0].varianceClasses shouldBe listOf(WorkPaperVarianceShare("UNEXPLAINED", "10.00"))
        view.boxes[0].unexplainedResidual shouldBe "10.00"
        view.unexplainedResidual shouldBe "10.00"
        view.supportingDocuments shouldBe listOf("A-1", "B-2")
    }
}
