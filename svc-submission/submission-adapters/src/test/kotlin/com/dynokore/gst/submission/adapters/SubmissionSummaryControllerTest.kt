package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.http.ComplianceRefusal
import com.dynokore.gst.submission.adapters.http.SubmissionListingHandler
import com.dynokore.gst.submission.adapters.http.SubmissionSummaryController
import com.dynokore.gst.submission.adapters.http.TransactionsController
import com.dynokore.gst.submission.adapters.http.TransactionsRefusalAdvice
import com.dynokore.gst.submission.application.listing.ListSubmissions
import com.dynokore.gst.submission.application.listing.ListingActions
import com.dynokore.gst.submission.application.listing.SourceRead
import com.dynokore.gst.submission.application.listing.SubmissionRowSource
import com.dynokore.gst.submission.application.listing.SubmissionSummaryQuery
import com.dynokore.gst.submission.application.replay.ReplayBySelection
import com.dynokore.gst.submission.application.replay.ReplaySingleVersion
import com.dynokore.gst.submission.application.replay.RequeueOutcome
import com.dynokore.gst.submission.contract.model.ReconciliationBucket
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

/** `getSubmissionSummary` served through TransactionsController and the SubmissionSummaryController (INV-AC-22165). */
class SubmissionSummaryControllerTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val entity = "E1"
    private val period = "P1"

    /** Machine types of the fixture: the full reader, a reader without the content key, and (absent) a stranger. */
    private val roleMap =
        OperationRoleMap { key ->
            when (key) {
                ListingActions.VIEW_STATUS_MACHINE, ListingActions.VIEW_SUMMARY_MACHINE -> {
                    mapOf("machine.$FULL" to CellAllowance.PERMITTED, "machine.$MASKED" to CellAllowance.PERMITTED)
                }

                ListingActions.VIEW_CONTENT -> {
                    mapOf("machine.$FULL" to CellAllowance.PERMITTED)
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

    private fun fixture(type: String): Fixture {
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
                SubmissionListingHandler(ListSubmissions(replica, source)),
                SubmissionSummaryController(SubmissionSummaryQuery(replica, source)),
            )
        return Fixture(controller, source)
    }

    private fun <T> bound(block: () -> T): T =
        CurrentDeadline.callWith(Deadline.after(Duration.ofSeconds(5), clock), block)

    private fun summary(
        f: Fixture,
        counterparty: String? = null,
        bucket: ReconciliationBucket? = null,
    ) = bound {
        f.controller.getSubmissionSummary(
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
            bucket,
        )
    }

    private fun refused(block: () -> Unit): Pair<Int, String> {
        val thrown = runCatching(block).exceptionOrNull()
        val refusal = thrown as? ComplianceRefusal ?: throw AssertionError("expected ComplianceRefusal, got $thrown")
        val response = TransactionsRefusalAdvice().refuse(refusal)
        val code = Regex("\"code\"\\s*:\\s*\"([^\"]*)\"").find(response.body!!)!!.groupValues[1]
        return response.statusCode.value() to code
    }

    @Test
    fun `INV-AC-22165 the summary row count and totals equal the sums of the rows the list returns over all pages`() {
        val f = fixture(FULL)
        listOf<String?>(null, "Acme").forEach { counterparty ->
            val items = mutableListOf<com.dynokore.gst.submission.contract.model.SubmissionSummary>()
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
            val response = summary(f, counterparty)
            response.statusCode.value() shouldBe 200
            val totals = response.body!!
            totals.rowCount shouldBe items.size
            totals.totalTaxableAmount.compareTo(items.fold(BigDecimal.ZERO) { a, i -> a + i.taxableAmount!! }) shouldBe
                0
            totals.totalGstAmount.compareTo(items.fold(BigDecimal.ZERO) { a, i -> a + i.gstAmount!! }) shouldBe 0
        }
        summary(f, "Acme").body!!.rowCount shouldBe 3
    }

    @Test
    fun `INV-AC-22165 a caller denied the view key gets 403 SRT-004 and the source has zero calls`() {
        val f = fixture("no-such-type")
        refused { summary(f) } shouldBe (403 to "SRT-004")
        f.source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-22165 a caller denied doc-view-content filtering on a counterparty gets 400 SRT-001`() {
        val f = fixture(MASKED)
        refused { summary(f, counterparty = "Acme") } shouldBe (400 to "SRT-001")
        f.source.calls shouldBe 0
        summary(f).body!!.rowCount shouldBe rows.size
    }

    @Test
    fun `INV-AC-22165 the bucket SENT_NOT_ACKNOWLEDGED gets 400 SRT-001 with zero source calls`() {
        val f = fixture(FULL)
        refused { summary(f, bucket = ReconciliationBucket.SENT_NOT_ACKNOWLEDGED) } shouldBe (400 to "SRT-001")
        f.source.calls shouldBe 0
    }

    private companion object {
        const val FULL = "summary-full"
        const val MASKED = "summary-masked"
    }
}
