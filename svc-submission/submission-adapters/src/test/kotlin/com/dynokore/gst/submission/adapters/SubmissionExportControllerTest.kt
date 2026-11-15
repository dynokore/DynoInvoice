package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.http.ComplianceRefusal
import com.dynokore.gst.submission.adapters.http.SubmissionExportController
import com.dynokore.gst.submission.adapters.http.SubmissionListingHandler
import com.dynokore.gst.submission.adapters.http.SubmissionSummaryController
import com.dynokore.gst.submission.adapters.http.TransactionsController
import com.dynokore.gst.submission.adapters.http.TransactionsRefusalAdvice
import com.dynokore.gst.submission.application.gstn.EntityGstns
import com.dynokore.gst.submission.application.listing.ExportSubmissions
import com.dynokore.gst.submission.application.listing.ListSubmissions
import com.dynokore.gst.submission.application.listing.ListingActions
import com.dynokore.gst.submission.application.listing.SourceRead
import com.dynokore.gst.submission.application.listing.SubmissionRowSource
import com.dynokore.gst.submission.application.listing.SubmissionSummaryQuery
import com.dynokore.gst.submission.application.replay.ReplayBySelection
import com.dynokore.gst.submission.application.replay.ReplaySingleVersion
import com.dynokore.gst.submission.application.replay.RequeueOutcome
import com.dynokore.gst.submission.contract.model.ReconciliationBucket
import com.dynokore.gst.submission.contract.model.SubmissionSummary
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
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
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

    /** Machine types of the fixture: the full reader, one without the content key, one without the status key. */
    private val roleMap =
        OperationRoleMap { key ->
            when (key) {
                ListingActions.VIEW_STATUS, ListingActions.VIEW_STATUS_MACHINE -> {
                    mapOf("machine.$FULL" to CellAllowance.PERMITTED, "machine.$NO_CONTENT" to CellAllowance.PERMITTED)
                }

                ListingActions.VIEW_CONTENT -> {
                    mapOf("machine.$FULL" to CellAllowance.PERMITTED, "machine.$NO_STATUS" to CellAllowance.PERMITTED)
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
    fun `INV-AC-22284 the formats XLSX and PDF get 400 SRT-001 and the source has zero calls`() {
        val f = fixture(FULL)
        listOf("XLSX", "PDF", "csv", "").forEach { format ->
            refused { export(f, format) } shouldBe (400 to "SRT-001")
        }
        f.source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-22284 a caller denied the view or content key gets 403 SRT-004 and the source has zero calls`() {
        listOf(NO_STATUS, NO_CONTENT, "no-such-type").forEach { type ->
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
    fun `INV-AC-22284 the operations gate shows getSubmissionReconciliation still in the stub set`() {
        // the placeholder call of OperationsStubs: a stub answers 501, an overridden method does not
        val f = fixture(FULL)
        bound { f.controller.getSubmissionReconciliation(entity, period) }.statusCode.value() shouldBe 501
        export(f).statusCode.value() shouldBe 200
    }

    private companion object {
        const val FULL = "export-full"
        const val NO_CONTENT = "export-no-content"
        const val NO_STATUS = "export-no-status"
    }
}
