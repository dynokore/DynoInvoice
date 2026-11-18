package com.dynokore.gst.reconciliation.application.workpaper.export

import com.dynokore.gst.reconciliation.application.run.RecordedRun
import com.dynokore.gst.reconciliation.application.run.RunBox
import com.dynokore.gst.reconciliation.application.run.RunCursor
import com.dynokore.gst.reconciliation.application.run.RunItems
import com.dynokore.gst.reconciliation.application.run.RunReader
import com.dynokore.gst.reconciliation.application.run.RunSnapshot
import com.dynokore.gst.reconciliation.application.run.RunUnmappedLine
import com.dynokore.gst.reconciliation.application.run.RunVarianceShare
import com.dynokore.gst.reconciliation.application.workpaper.GetWorkPaper
import com.dynokore.gst.reconciliation.application.workpaper.RunAnnotations
import com.dynokore.gst.reconciliation.application.workpaper.RunAttestations
import com.dynokore.gst.reconciliation.application.workpaper.StoredAnnotation
import com.dynokore.gst.reconciliation.application.workpaper.StoredAttestation
import com.dynokore.gst.reconciliation.application.workpaper.WorkPaperVarianceShare
import com.dynokore.gst.reconciliation.domain.annotation.Annotation
import com.dynokore.gst.reconciliation.domain.run.ReconcilingItem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ExportWorkPaperTest {
    private val log = mutableListOf<String>()
    private val rendered = mutableListOf<Pair<WorkPaperExport, ExportFormat>>()
    private val sourceCalls = mutableListOf<Triple<String, String, LocalDate>>()
    private val itemReads = mutableListOf<Pair<String, String>>()
    private var unavailable = false
    private val stored = mutableListOf<ReconcilingItem>()

    private fun runBox(
        box: String,
        submitted: String,
        filed: String,
        variance: String,
        residual: String,
    ) = RunBox(box, BigDecimal(submitted), BigDecimal(filed), BigDecimal(variance), emptyList(), BigDecimal(residual))

    private val attestedAt = Instant.parse("2026-11-01T09:30:00Z")
    private val run = RecordedRun("R1", "E1", "P1", 3, Instant.parse("2026-10-30T00:00:00Z"), "F5")
    private val b1 =
        runBox("B1", "90.00", "100.00", "10.00", "10.00")
            .copy(varianceClasses = listOf(RunVarianceShare("UNEXPLAINED", BigDecimal("10.00"))))
    private val b2 = runBox("B2", "0.00", "0.00", "0.00", "0.00")
    private val unmapped = RunUnmappedLine("D5", 1, 3, "XX")
    private val snapshot = RunSnapshot(run, listOf(b1, b2), BigDecimal("10.00"), listOf(unmapped), true)

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
            ): RunSnapshot? = if (tenant == "T1" && runId == "R1") snapshot else null
        }

    private val annotations =
        object : RunAnnotations {
            private val row = StoredAnnotation("R1", Annotation("B1", null, listOf("D9")), "U1", attestedAt)

            override fun latest(
                tenant: String,
                runId: String,
                box: String,
            ) = all(tenant, runId).firstOrNull { it.annotation.box == box }

            override fun all(
                tenant: String,
                runId: String,
            ) = if (tenant == "T1" && runId == "R1") listOf(row) else emptyList()

            override fun insert(
                tenant: String,
                stored: StoredAnnotation,
            ) = error("export stores nothing")
        }

    private val attestations =
        RunAttestations { t, r -> if (t == "T1" && r == "R1") StoredAttestation("U1", attestedAt) else null }

    private val itemsPort =
        RunItems { t, r ->
            log += "items"
            itemReads += t to r
            if (t == "T1" && r == "R1") stored else emptyList()
        }

    private val source =
        RelaxedControlsSource { t, e, on ->
            log += "relaxed"
            sourceCalls += Triple(t, e, on)
            if (unavailable) throw RelaxedControlsUnavailable("not current")
            listOf(ExportRelaxedControl("C1", "text"))
        }

    private val renderer =
        WorkPaperRenderer { export, format ->
            log += "render"
            rendered += export to format
            val type = if (format == ExportFormat.PDF) ExportContentTypes.PDF else ExportContentTypes.SPREADSHEET
            RenderedExport(type, byteArrayOf(7, format.ordinal.toByte()))
        }

    private val clock = Clock.fixed(Instant.parse("2026-11-18T00:00:00Z"), ZoneOffset.UTC)

    private fun item(
        box: String,
        doc: String,
        position: Pair<Int, Int>,
        amount: String,
        vararg refs: String,
    ) = ReconcilingItem(box, doc, position.first, position.second, BigDecimal(amount), refs.toList())

    private fun seed() {
        stored.clear()
        stored.addAll(
            listOf(
                item("B1", "D1", 2 to 1, "60.00", "REF-D1"),
                item("B1", "D1", 2 to 2, "40.00", "REF-D1"),
                item("B1", "D2", 1 to 1, "-10.00", "D2"),
            ),
        )
    }

    private val view = GetWorkPaper(reader, annotations, attestations)

    private fun useCase() = ExportWorkPaper(view, annotations, itemsPort, source, renderer, clock)

    private fun expected(format: ExportFormat): Pair<WorkPaperExport, ExportFormat> =
        WorkPaperExport(
            runId = "R1",
            entityRef = "E1",
            periodRef = "P1",
            preparedAt = "2026-10-30T00:00:00Z",
            returnVersion = 3,
            returnForm = "F5",
            boxes =
                listOf(
                    ExportBox(
                        "B1",
                        "90.00",
                        "100.00",
                        "10.00",
                        listOf(WorkPaperVarianceShare("UNEXPLAINED", "10.00")),
                        listOf("D1", "D2", "D9"),
                        listOf(
                            ExportReconcilingItem("D1", 2, 1, "60.00", listOf("REF-D1")),
                            ExportReconcilingItem("D1", 2, 2, "40.00", listOf("REF-D1")),
                            ExportReconcilingItem("D2", 1, 1, "-10.00", listOf("D2")),
                        ),
                    ),
                    ExportBox("B2", "0.00", "0.00", "0.00", emptyList(), emptyList(), emptyList()),
                ),
            unexplainedResidual = "10.00",
            relaxedControls = listOf(ExportRelaxedControl("C1", "text")),
            attestation = ExportAttestation("U1", "2026-11-01T09:30:00Z"),
            unmappedLines = listOf(ExportUnmappedLine("D5", 1, 3, "XX")),
        ) to format

    @Test
    fun `INV-AC-24471 the renderer is called once per call with the asked format and the full model`() {
        seed()
        val uc = useCase()
        val a = uc.execute("T1", "R1", ExportFormat.SPREADSHEET)
        val b = uc.execute("T1", "R1", ExportFormat.PDF)
        rendered shouldBe listOf(expected(ExportFormat.SPREADSHEET), expected(ExportFormat.PDF))
        rendered[0].first shouldBe rendered[1].first
        (a as ExportOutcome.Exported).rendered.contentType shouldBe ExportContentTypes.SPREADSHEET
        a.rendered.bytes.toList() shouldBe listOf<Byte>(7, 1)
        (b as ExportOutcome.Exported).rendered.contentType shouldBe ExportContentTypes.PDF
        b.rendered.bytes.toList() shouldBe listOf<Byte>(7, 0)
    }

    @Test
    fun `INV-AC-24661 every export box holds the variance classes of its work-paper box`() {
        seed()
        useCase().execute("T1", "R1", ExportFormat.SPREADSHEET)
        val exported = rendered.single().first.boxes
        val viewBoxes = view.execute("T1", "R1")!!.boxes
        exported.map { it.varianceClasses } shouldBe viewBoxes.map { it.varianceClasses }
        exported.map { it.varianceClasses } shouldBe
            listOf(listOf(WorkPaperVarianceShare("UNEXPLAINED", "10.00")), emptyList())
    }

    @Test
    fun `INV-AC-24471 the relaxed controls are read for tenant, entity and the UTC date of the clock`() {
        seed()
        useCase().execute("T1", "R1", ExportFormat.PDF)
        sourceCalls shouldBe listOf(Triple("T1", "E1", LocalDate.of(2026, 11, 18)))
        log shouldBe listOf("relaxed", "items", "render")
    }

    @Test
    fun `INV-AC-24471 the items are read for the tenant and the run`() {
        seed()
        useCase().execute("T1", "R1", ExportFormat.PDF)
        itemReads shouldBe listOf("T1" to "R1")
    }

    @Test
    fun `INV-AC-24471 an unknown run is RunNotFound and nothing else is read or rendered`() {
        seed()
        useCase().execute("T1", "R9", ExportFormat.SPREADSHEET) shouldBe ExportOutcome.RunNotFound
        log shouldBe emptyList()
        sourceCalls shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24471 a policy replica that is not current is RelaxedControlsNotCurrent and nothing is rendered`() {
        seed()
        unavailable = true
        useCase().execute("T1", "R1", ExportFormat.SPREADSHEET) shouldBe ExportOutcome.RelaxedControlsNotCurrent
        rendered shouldBe emptyList()
        log shouldBe listOf("relaxed")
    }

    @Test
    fun `INV-AC-24471 a stored item of a box the view does not hold throws naming the box and renders nothing`() {
        seed()
        stored += item("B7", "D3", 1 to 1, "5.00", "D3")
        val e = shouldThrow<IllegalArgumentException> { useCase().execute("T1", "R1", ExportFormat.SPREADSHEET) }
        e.message!! shouldContain "B7"
        rendered shouldBe emptyList()
    }

    @Test
    fun `INV-AC-24471 supporting documents are distinct and sorted, items of another box stay out`() {
        stored.clear()
        stored.addAll(
            listOf(
                item("B1", "D2", 1 to 1, "1.00", "D2"),
                item("B2", "D4", 1 to 1, "2.00", "D4"),
                item("B1", "D2", 1 to 2, "3.00", "D2"),
                item("B1", "D1", 1 to 1, "4.00", "D1"),
            ),
        )
        useCase().execute("T1", "R1", ExportFormat.PDF)
        val boxes = rendered.single().first.boxes
        boxes[0].supportingDocuments shouldBe listOf("D1", "D2", "D9")
        boxes[0].items.map { it.amount } shouldBe listOf("1.00", "3.00", "4.00")
        boxes[1].supportingDocuments shouldBe listOf("D4")
        boxes[1].items.map { it.amount } shouldBe listOf("2.00")
    }
}
