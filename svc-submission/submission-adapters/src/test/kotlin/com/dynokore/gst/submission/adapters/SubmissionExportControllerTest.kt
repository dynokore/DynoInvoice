package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.http.ComplianceRefusal
import com.dynokore.gst.submission.adapters.http.SubmissionExportController
import com.dynokore.gst.submission.adapters.http.SubmissionListingHandler
import com.dynokore.gst.submission.adapters.http.SubmissionReconciliationController
import com.dynokore.gst.submission.adapters.http.SubmissionSummaryController
import com.dynokore.gst.submission.adapters.http.TransactionsController
import com.dynokore.gst.submission.adapters.http.TransactionsRefusalAdvice
import com.dynokore.gst.submission.application.gstn.EntityGstns
import com.dynokore.gst.submission.application.listing.ExportSubmissions
import com.dynokore.gst.submission.application.listing.GetSubmissionReconciliation
import com.dynokore.gst.submission.application.listing.ListSubmissions
import com.dynokore.gst.submission.application.listing.ListingActions
import com.dynokore.gst.submission.application.listing.SourceRead
import com.dynokore.gst.submission.application.listing.SubmissionRowSource
import com.dynokore.gst.submission.application.listing.SubmissionSummaryQuery
import com.dynokore.gst.submission.application.replay.ReplayBySelection
import com.dynokore.gst.submission.application.replay.ReplaySingleVersion
import com.dynokore.gst.submission.application.replay.RequeueOutcome
import com.dynokore.gst.submission.contract.model.SubmissionSummary
import com.dynokore.gst.submission.contract.model.WithdrawRequest
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.gst.submission.domain.listing.SubmissionRow
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.serviceruntime.identity.MachineTokenFilter
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.MachinePrincipalFixtures
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.HttpServletRequest
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** `exportSubmissions` served through TransactionsController and the SubmissionExportController (INV-AC-22284). */
class SubmissionExportControllerTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val entity = "E1"
    private val period = "P1"

    /** Machine types: full reader, no content key, no machine status key, and only `submission.view-status`. */
    private val roleMap =
        OperationRoleMap { key ->
            when (key) {
                ListingActions.VIEW_STATUS_MACHINE -> {
                    mapOf("machine.$FULL" to CellAllowance.PERMITTED, "machine.$NO_CONTENT" to CellAllowance.PERMITTED)
                }

                ListingActions.VIEW_STATUS -> {
                    mapOf("machine.$VIEW_ONLY" to CellAllowance.PERMITTED)
                }

                ListingActions.VIEW_CONTENT -> {
                    mapOf(
                        "machine.$FULL" to CellAllowance.PERMITTED,
                        "machine.$NO_STATUS" to CellAllowance.PERMITTED,
                        "machine.$VIEW_ONLY" to CellAllowance.PERMITTED,
                    )
                }

                else -> {
                    emptyMap()
                }
            }
        }

    private class RecordingSource(
        private val rows: List<SubmissionRow>,
    ) : SubmissionRowSource {
        var calls = 0

        override fun rows(
            tenant: TenantId,
            entityRef: String,
            periodRef: String,
        ): SourceRead {
            calls++
            return SourceRead(rows, 0)
        }
    }

    private fun row(
        doc: Int,
        version: Int,
        state: LifecycleState,
        name: String,
        taxable: String,
    ) = SubmissionRow(
        docUuid = "00000000-0000-0000-0000-00000000000$doc",
        version = version,
        submissionType = SubmissionKind.TYPE_1A,
        state = state,
        dueDate = LocalDate.of(2026, 11, 10),
        documentNumber = "N-$doc-$version",
        documentTypeCode = "380",
        direction = Direction.SALE,
        counterpartyName = name,
        counterpartyUen = "UEN-$name",
        transactionDate = LocalDate.of(2026, 11, doc),
        taxableAmount = BigDecimal(taxable),
        gstAmount = BigDecimal(taxable).multiply(BigDecimal("0.09")).setScale(2, java.math.RoundingMode.HALF_UP),
        categoryCodes = setOf("SR"),
        submittedAt = Instant.parse("2026-11-02T00:00:00Z"),
        acknowledgedAt = null,
        acknowledgementId = null,
    )

    private val rows =
        listOf(
            row(1, 1, LifecycleState.SUPERSEDED, "Acme", "100.00"),
            row(1, 2, LifecycleState.ACKNOWLEDGED, "Acme", "200.00"),
            row(2, 1, LifecycleState.REJECTED, "Globex", "50.00"),
            row(3, 1, LifecycleState.QUEUED, "Acme", "300.50"),
            row(4, 1, LifecycleState.DEAD_LETTERED, "Initech", "400.00"),
        )

    private class Fixture(
        val controller: TransactionsController,
        val source: RecordingSource,
    )

    private fun fixture(
        type: String,
        gstn: String? = "M90000002B",
    ): Fixture {
        val replica = AuthorityReplica(clock, roleMap).also { it.onSnapshotComplete(0) }
        val caller: Principal =
            MachinePrincipalFixtures.machine(
                "m1",
                tenant,
                MachineEntityCoverage.Entity(entity),
                type,
            )
        val source = RecordingSource(rows)
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                check(method.name == "getAttribute") { "unexpected request call ${method.name}" }
                if (args[0] == MachineTokenFilter.PRINCIPAL_ATTRIBUTE) caller else null
            } as HttpServletRequest
        val controller =
            TransactionsController(
                ReplayBySelection(replica, { _, _ -> emptyList() }, { _, _, _ -> RequeueOutcome.REQUEUED }),
                ReplaySingleVersion(replica, { _, _, _ -> null }, { _, _, _ -> RequeueOutcome.REQUEUED }),
                clock,
                object : ObjectProvider<HttpServletRequest> {
                    override fun getObject(): HttpServletRequest = request
                },
                SubmissionListingHandler(ListSubmissions(replica, source), clock),
                SubmissionSummaryController(SubmissionSummaryQuery(replica, source), clock),
                SubmissionReconciliationController(GetSubmissionReconciliation(replica, source), clock),
                SubmissionExportController(ExportSubmissions(replica, source, EntityGstns { _, _ -> gstn }, clock)),
            )
        return Fixture(controller, source)
    }

    private fun <T> bound(block: () -> T): T =
        CurrentDeadline.callWith(Deadline.after(Duration.ofSeconds(5), clock), block)

    private fun export(
        f: Fixture,
        format: String = "CSV",
        counterparty: String? = null,
    ) = bound {
        f.controller.exportSubmissions(
            entity,
            period,
            format,
            null,
            null,
            null,
            null,
            counterparty,
            null,
            null,
            null,
            null,
        )
    }

    private fun refused(block: () -> Unit): Pair<Int, String> {
        val thrown = runCatching(block).exceptionOrNull()
        val refusal = thrown as? ComplianceRefusal ?: throw AssertionError("expected ComplianceRefusal, got $thrown")
        val response = TransactionsRefusalAdvice().refuse(refusal)
        val code = Regex("\"code\"\\s*:\\s*\"([^\"]*)\"").find(response.body!!)!!.groupValues[1]
        return response.statusCode.value() to code
    }

    private fun text(response: org.springframework.http.ResponseEntity<org.springframework.core.io.Resource>) =
        response.body!!.contentAsByteArray.toString(Charsets.UTF_8)

    /** The rows `listSubmissions` returns over all pages of size 2. */
    private fun listed(
        f: Fixture,
        counterparty: String?,
    ): List<SubmissionSummary> {
        val items = mutableListOf<SubmissionSummary>()
        var cursor: String? = null
        do {
            val page =
                bound {
                    f.controller.listSubmissions(
                        entity,
                        period,
                        null,
                        null,
                        null,
                        null,
                        counterparty,
                        null,
                        null,
                        null,
                        null,
                        null,
                        2,
                        cursor,
                    )
                }.body!!
            items += page.items
            cursor = page.nextCursor
        } while (cursor != null)
        return items
    }

    @Test
    fun `INV-AC-22284 the export headers equal the sums of the rows the list returns over all pages`() {
        val f = fixture(FULL)
        listOf<String?>(null, "Acme").forEach { counterparty ->
            val items = listed(f, counterparty)
            val response = export(f, counterparty = counterparty)
            response.statusCode.value() shouldBe 200
            response.headers.contentType.toString() shouldBe "text/csv"
            response.headers.getFirst("X-Export-Row-Count") shouldBe items.size.toString()
            BigDecimal(response.headers.getFirst("X-Export-Total-Taxable-Amount")!!)
                .compareTo(items.fold(BigDecimal.ZERO) { a, i -> a + i.taxableAmount!! }) shouldBe 0
            BigDecimal(response.headers.getFirst("X-Export-Total-Gst-Amount")!!)
                .compareTo(items.fold(BigDecimal.ZERO) { a, i -> a + i.gstAmount!! }) shouldBe 0
            // header line, values line, blank line, column line, then the data lines
            text(response)
                .trimEnd()
                .split("\r\n")
                .drop(4)
                .size shouldBe items.size
        }
    }

    @Test
    fun `INV-AC-22284 the header block carries the GSTN and the acting user ID`() {
        val values = text(export(fixture(FULL))).split("\r\n")[1].split(",")
        values[1] shouldBe "M90000002B"
        values[4] shouldBe "m1"
    }

    @Test
    @Suppress("ktlint:standard:max-line-length", "MaxLineLength") // the mandated case name is longer than 120
    fun `INV-AC-22284 the format PDF and any value other than CSV and XLSX get 400 SRT-001 and the source has zero calls`() {
        val f = fixture(FULL)
        listOf("PDF", "csv", "").forEach { format ->
            refused { export(f, format) } shouldBe (400 to "SRT-001")
        }
        f.source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-22284 a machine caller without machine submissions read gets 403 SRT-004 and no source call`() {
        listOf(NO_STATUS, VIEW_ONLY, "no-such-type").forEach { type ->
            val f = fixture(type)
            refused { export(f) } shouldBe (403 to "SRT-004")
            f.source.calls shouldBe 0
        }
    }

    @Test
    fun `INV-AC-22284 an entity with no GSTN gets 503 SRT-013 and the source has zero calls`() {
        val f = fixture(FULL, gstn = null)
        refused { export(f) } shouldBe (503 to "SRT-013")
        f.source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-22284 the unowned operation getTransaction still answers 501`() {
        val f = fixture(FULL)
        f.controller
            .getTransaction(UUID.fromString("00000000-0000-0000-0000-0000000000d1"))
            .statusCode
            .value() shouldBe
            501
        f.source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-22284 the unowned operation withdrawSubmission still answers 501`() {
        val f = fixture(FULL)
        f.controller
            .withdrawSubmission(UUID.fromString("00000000-0000-0000-0000-0000000000d1"), WithdrawRequest("placeholder"))
            .statusCode
            .value() shouldBe 501
        f.source.calls shouldBe 0
    }

    /** The data lines of a CSV response: the cells of each row, after the header block and the column line. */
    private fun dataCells(response: org.springframework.http.ResponseEntity<org.springframework.core.io.Resource>) =
        text(response)
            .trimEnd()
            .split("\r\n")
            .drop(4)
            .map { it.split(",") }

    @Test
    fun `INV-AC-22284 a machine caller without doc view content gets the 200 CSV with the content cells empty`() {
        val full = export(fixture(FULL))
        val f = fixture(NO_CONTENT)
        val masked = export(f)
        masked.statusCode.value() shouldBe 200
        masked.headers.contentType.toString() shouldBe "text/csv"
        f.source.calls shouldBe 1
        val columns = text(full).split("\r\n")[3].split(",")
        val maskedColumns =
            listOf("documentNumber", "counterpartyName", "counterpartyUen", "taxableAmount", "gstAmount")
        val fullRows = dataCells(full)
        val maskedRows = dataCells(masked)
        maskedRows.size shouldBe fullRows.size
        maskedColumns.forEach { name ->
            val at = columns.indexOf(name)
            (at >= 0) shouldBe true
            maskedRows.forEach { it[at] shouldBe "" }
        }
        fullRows.zip(maskedRows).forEach { (a, b) ->
            columns.indices.filter { columns[it] !in maskedColumns }.forEach { b[it] shouldBe a[it] }
        }
        listOf("X-Export-Row-Count", "X-Export-Total-Taxable-Amount", "X-Export-Total-Gst-Amount").forEach {
            masked.headers.getFirst(it) shouldBe full.headers.getFirst(it)
        }
    }

    @Test
    fun `INV-AC-22284 a masked caller filtering on a masked fact gets 400 SRT-001 and a direction filter gets 200`() {
        val f = fixture(NO_CONTENT)
        refused { export(f, counterparty = "Acme") } shouldBe (400 to "SRT-001")
        f.source.calls shouldBe 0
        bound {
            f.controller.exportSubmissions(
                entity,
                period,
                "CSV",
                "SALE",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
            )
        }.statusCode.value() shouldBe 200
    }

    private val xlsxType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

    private fun sheetOf(response: org.springframework.http.ResponseEntity<org.springframework.core.io.Resource>) =
        XSSFWorkbook(ByteArrayInputStream(response.body!!.contentAsByteArray)).use { wb ->
            val sheet = wb.getSheet("Submissions")
            (0..sheet.lastRowNum).map { r -> sheet.getRow(r)?.map { it.toString() }.orEmpty() }
        }

    @Test
    fun `INV-AC-16345 format XLSX serves the headers, type and fixed file name of the same rows`() {
        val f = fixture(FULL)
        val csv = export(f)
        val xlsx = export(f, "XLSX")
        xlsx.statusCode.value() shouldBe 200
        xlsx.headers.contentType.toString() shouldBe xlsxType
        xlsx.headers.getFirst("Content-Disposition") shouldBe "attachment; filename=\"submissions.xlsx\""
        listOf("X-Export-Row-Count", "X-Export-Total-Taxable-Amount", "X-Export-Total-Gst-Amount").forEach {
            xlsx.headers.getFirst(it) shouldBe csv.headers.getFirst(it)
        }
        val sheet = sheetOf(xlsx)
        (sheet.size - 4).toString() shouldBe xlsx.headers.getFirst("X-Export-Row-Count")
        sheet[1][0] shouldBe entity
        sheet[1][4] shouldBe "m1"
    }

    @Test
    fun `INV-AC-16345 a caller without doc view content gets the workbook with the content cells empty`() {
        val full = sheetOf(export(fixture(FULL), "XLSX"))
        val f = fixture(NO_CONTENT)
        val masked = sheetOf(export(f, "XLSX"))
        f.source.calls shouldBe 1
        val columns = full[3]
        val maskedColumns =
            listOf("documentNumber", "counterpartyName", "counterpartyUen", "taxableAmount", "gstAmount")
        masked.size shouldBe full.size
        full.drop(4).zip(masked.drop(4)).forEach { (a, b) ->
            columns.indices.forEach { i ->
                if (columns[i] in maskedColumns) b[i] shouldBe "" else b[i] shouldBe a[i]
            }
        }
    }

    @Test
    fun `INV-AC-16345 a masked caller filtering on a masked fact is refused for XLSX and the source is not called`() {
        val f = fixture(NO_CONTENT)
        refused { export(f, "XLSX", counterparty = "Acme") } shouldBe (400 to "SRT-001")
        f.source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-16345 the XLSX file name and type are fixed and the PDF format is still refused`() {
        val f = fixture(FULL)
        val hostile = "../x\r\nSet-Cookie: a"
        val xlsx =
            bound {
                f.controller.exportSubmissions(
                    entity,
                    hostile,
                    "XLSX",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                )
            }
        xlsx.headers.getFirst("Content-Disposition") shouldBe "attachment; filename=\"submissions.xlsx\""
        refused { export(f, "PDF") } shouldBe (400 to "SRT-001")
    }

    private companion object {
        const val FULL = "export-full"
        const val NO_CONTENT = "export-no-content"
        const val NO_STATUS = "export-no-status"
        const val VIEW_ONLY = "export-view-only"
    }
}
