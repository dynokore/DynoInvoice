package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.http.ComplianceRefusal
import com.dynokore.gst.submission.adapters.http.SubmissionListingHandler
import com.dynokore.gst.submission.adapters.http.SubmissionReconciliationController
import com.dynokore.gst.submission.adapters.http.SubmissionSummaryController
import com.dynokore.gst.submission.adapters.http.TransactionsController
import com.dynokore.gst.submission.adapters.http.TransactionsRefusalAdvice
import com.dynokore.gst.submission.application.listing.GetSubmissionReconciliation
import com.dynokore.gst.submission.application.listing.ListSubmissions
import com.dynokore.gst.submission.application.listing.ListingActions
import com.dynokore.gst.submission.application.listing.ReconciliationActions
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
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

/** `getSubmissionReconciliation` served through a real TransactionsController and its delegate (INV-AC-16328). */
class SubmissionReconciliationControllerTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val entity = "E1"
    private val period = "P1"

    private val roleMap =
        OperationRoleMap { key ->
            when (key) {
                ReconciliationActions.READ_MACHINE, ListingActions.VIEW_STATUS_MACHINE -> {
                    mapOf("machine.$READER" to CellAllowance.PERMITTED)
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
        taxable: String,
        ackDeadline: Instant? = null,
    ) = SubmissionRow(
        docUuid = "00000000-0000-0000-0000-00000000000$doc",
        version = version,
        submissionType = SubmissionKind.TYPE_1A,
        state = state,
        dueDate = LocalDate.of(2026, 11, 10),
        documentNumber = "N-$doc-$version",
        documentTypeCode = "380",
        direction = Direction.SALE,
        counterpartyName = "Acme",
        counterpartyUen = "UEN-Acme",
        transactionDate = LocalDate.of(2026, 11, doc),
        taxableAmount = BigDecimal(taxable),
        gstAmount = BigDecimal(taxable).multiply(BigDecimal("0.09")).setScale(2, java.math.RoundingMode.HALF_UP),
        categoryCodes = setOf("SR"),
        submittedAt = Instant.parse("2026-11-02T00:00:00Z"),
        acknowledgedAt = null,
        acknowledgementId = null,
        acknowledgementDeadline = ackDeadline,
    )

    private val overdue = Instant.parse("2026-11-12T00:00:00Z")
    private val notYetDue = Instant.parse("2026-11-14T00:00:00Z")

    /** X acknowledged, Y sent past its deadline (two versions), Z rejected, W dead-lettered, R never sent, V not yet due. */
    private val rows =
        listOf(
            row(1, 1, LifecycleState.SUPERSEDED, "100.00"),
            row(1, 2, LifecycleState.ACKNOWLEDGED, "200.00"),
            row(2, 1, LifecycleState.SENT_UNCONFIRMED, "10.00", overdue),
            row(2, 2, LifecycleState.SENT, "20.00", overdue),
            row(3, 1, LifecycleState.REJECTED, "50.00"),
            row(4, 1, LifecycleState.DEAD_LETTERED, "400.00"),
            row(5, 1, LifecycleState.QUEUED, "300.50"),
            row(6, 1, LifecycleState.SENT, "70.00", notYetDue),
        )

    private class Fixture(
        val controller: TransactionsController,
        val delegate: SubmissionReconciliationController,
        val source: RecordingSource,
    )

    private fun fixture(type: String): Fixture {
        val replica = AuthorityReplica(clock, roleMap).also { it.onSnapshotComplete(0) }
        val caller: Principal =
            MachinePrincipalFixtures.machine("m1", tenant, MachineEntityCoverage.Entity(entity), type)
        val source = RecordingSource(rows)
        val request =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServletRequest::class.java)) { _, method, args ->
                check(method.name == "getAttribute") { "unexpected request call ${method.name}" }
                if (args[0] == MachineTokenFilter.PRINCIPAL_ATTRIBUTE) caller else null
            } as HttpServletRequest
        val delegate = SubmissionReconciliationController(GetSubmissionReconciliation(replica, source), clock)
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
                delegate,
            )
        return Fixture(controller, delegate, source)
    }

    private fun <T> bound(block: () -> T): T =
        CurrentDeadline.callWith(Deadline.after(Duration.ofSeconds(5), clock), block)

    private fun refused(block: () -> Unit): Pair<Int, String> {
        val thrown = runCatching(block).exceptionOrNull()
        val refusal = thrown as? ComplianceRefusal ?: throw AssertionError("expected ComplianceRefusal, got $thrown")
        val response = TransactionsRefusalAdvice().refuse(refusal)
        val code = Regex("\"code\"\\s*:\\s*\"([^\"]*)\"").find(response.body!!)!!.groupValues[1]
        return response.statusCode.value() to code
    }

    private fun reconciliation(f: Fixture) = bound { f.controller.getSubmissionReconciliation(entity, period) }

    private fun listedDocs(
        f: Fixture,
        bucket: ReconciliationBucket,
    ): Set<String> {
        val docs = mutableSetOf<String>()
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
                        null,
                        null,
                        null,
                        null,
                        bucket,
                        null,
                        1,
                        cursor,
                    )
                }.body!!
            docs += page.items.map { it.docUuid.toString() }
            cursor = page.nextCursor
        } while (cursor != null)
        return docs
    }

    @Test
    fun `INV-AC-16328 the five classes are served over HTTP for X Y Z W and R in the contract order`() {
        val f = fixture(READER)
        val response = reconciliation(f)
        response.statusCode.value() shouldBe 200
        val view = response.body!!
        view.entityRef shouldBe entity
        view.periodRef shouldBe period
        view.buckets.map { it.bucket } shouldBe ReconciliationBucket.entries.toList()
        view.buckets.map { it.count } shouldBe listOf(1, 1, 1, 1, 1)
        view.buckets.map { it.totalAmount.toPlainString() } shouldBe
            listOf("200.00", "20.00", "50.00", "400.00", "300.50")
    }

    @Test
    fun `INV-AC-16328 each class count equals the distinct documents of the list under that bucket over all pages`() {
        val f = fixture(READER)
        val view = reconciliation(f).body!!
        view.buckets.forEach { total ->
            listedDocs(f, total.bucket).size shouldBe total.count
        }
        view.buckets.first { it.bucket == ReconciliationBucket.SENT_NOT_ACKNOWLEDGED }.count shouldBe 1
        listedDocs(f, ReconciliationBucket.SENT_NOT_ACKNOWLEDGED) shouldBe setOf("00000000-0000-0000-0000-000000000002")
    }

    @Test
    fun `INV-AC-16328 a machine caller with the granted key is served and a person without a tenant is 401 SRT-003`() {
        reconciliation(fixture(READER)).statusCode.value() shouldBe 200
        val replica = AuthorityReplica(clock, roleMap).also { it.onSnapshotComplete(0) }
        val source = RecordingSource(rows)
        val delegate = SubmissionReconciliationController(GetSubmissionReconciliation(replica, source), clock)
        refused { bound { delegate.reconcile(Principal("holder", PrincipalKind.HUMAN), entity, period) } } shouldBe
            (401 to "SRT-003")
        source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-16328 a denied key is 403 SRT-004 with zero source calls`() {
        val f = fixture("no-such-type")
        refused { reconciliation(f) } shouldBe (403 to "SRT-004")
        f.source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-16328 the operation is no longer a 501 stub`() {
        reconciliation(fixture(READER)).statusCode.value() shouldNotBe 501
    }

    private companion object {
        const val READER = "recon-reader"
    }
}
