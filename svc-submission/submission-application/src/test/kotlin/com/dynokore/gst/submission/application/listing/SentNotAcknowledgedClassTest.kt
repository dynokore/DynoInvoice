package com.dynokore.gst.submission.application.listing

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
import com.dynokore.gst.submission.application.gstn.EntityGstns
import com.dynokore.gst.submission.domain.listing.Direction
import com.dynokore.gst.submission.domain.listing.SubmissionFilter
import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.gst.submission.domain.listing.SubmissionRow
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.reconciliation.ReconciliationBucket
import com.dynokore.platform.serviceruntime.replica.AuthorityChanged
import com.dynokore.platform.serviceruntime.replica.AuthorityRecord
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.GrantScope
import com.dynokore.platform.serviceruntime.replica.MachineEntityCoverage
import com.dynokore.platform.serviceruntime.replica.MachinePrincipalFixtures
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.replica.RoleGrant
import com.dynokore.platform.serviceruntime.replica.RoleGrantState
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The list, summary and export classify with the stored deadline and the request instant (INV-AC-22763). */
class SentNotAcknowledgedClassTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC)
    private val deadline = Deadline.after(Duration.ofSeconds(5), clock)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val entityRef = "E1"
    private val periodRef = "P1"
    private val holder = Principal("holder", PrincipalKind.HUMAN)
    private val due = Instant.parse("2026-03-01T10:00:00Z")
    private val later = due.plusSeconds(1)

    private val roleMap =
        OperationRoleMap { key ->
            OperationRoles.rolesFor(DeploymentForm.SAAS, key).mapValues { CellAllowance.valueOf(it.value.name) }
        }

    /** A role of the catalogue that holds both scoped keys; never written as a literal (INV-Q-10027). */
    private fun roleHoldingBoth(): String =
        (roleMap.rolesFor(ListingActions.VIEW_STATUS).keys intersect roleMap.rolesFor(ListingActions.VIEW_CONTENT).keys)
            .sorted()
            .first()

    private fun replica(): AuthorityReplica =
        AuthorityReplica(clock, roleMap).also { replica ->
            replica.onSnapshotComplete(0)
            val role = roleHoldingBoth()
            val grant = RoleGrant(role, GrantScope.ENTITY, entityRef, clock.instant().minusSeconds(60))
            val record = AuthorityRecord.RoleGrantRecord("$role-$entityRef", RoleGrantState.ACTIVE, grant)
            replica.accept(AuthorityChanged(tenant, holder.id, 1, record))
        }

    private val source =
        object : SubmissionRowSource {
            override fun rows(
                tenant: TenantId,
                entityRef: String,
                periodRef: String,
            ) = SourceRead(rows, 0)
        }

    private fun row(
        doc: String,
        version: Int,
        state: LifecycleState,
        stored: Instant?,
    ) = SubmissionRow(
        docUuid = doc,
        version = version,
        submissionType = SubmissionKind.TYPE_1A,
        state = state,
        dueDate = LocalDate.of(2026, 11, 10),
        documentNumber = "N-$doc-$version",
        documentTypeCode = "380",
        direction = Direction.SALE,
        counterpartyName = "Acme",
        counterpartyUen = "UEN-Acme",
        transactionDate = LocalDate.of(2026, 11, version),
        taxableAmount = BigDecimal("100.00"),
        gstAmount = BigDecimal("9.00"),
        categoryCodes = setOf("SR"),
        submittedAt = null,
        acknowledgedAt = if (state == LifecycleState.ACKNOWLEDGED) Instant.parse("2026-03-01T00:00:00Z") else null,
        acknowledgementId = if (state == LifecycleState.ACKNOWLEDGED) "ACK-$doc-$version" else null,
        acknowledgementDeadline = stored,
    )

    /** X: first version carries a far deadline, last one [due]; Y as X with SENT_UNCONFIRMED; Z none; W; R. */
    private val rows =
        listOf(
            row("X", 1, LifecycleState.SUPERSEDED, Instant.parse("2030-01-01T00:00:00Z")),
            row("X", 2, LifecycleState.SENT, due),
            row("Y", 1, LifecycleState.SUPERSEDED, Instant.parse("2030-01-01T00:00:00Z")),
            row("Y", 2, LifecycleState.SENT_UNCONFIRMED, due),
            row("Z", 1, LifecycleState.SENT, null),
            row("W", 1, LifecycleState.ACKNOWLEDGED, null),
            row("W", 2, LifecycleState.SENT, due.minusSeconds(3600)),
            row("R", 1, LifecycleState.REJECTED, due),
        )

    private fun listed(
        filter: SubmissionFilter,
        now: Instant,
    ): List<Pair<String, Int>> {
        val list = ListSubmissions(replica(), source)
        val all = mutableListOf<Pair<String, Int>>()
        var cursor: String? = null
        do {
            val request =
                ListSubmissionsRequest(tenant, holder, entityRef, periodRef, filter, null, cursor, 2, 500, now)
            val page = list.handle(request, deadline).shouldBeInstanceOf<ListSubmissionsResult.Page>()
            all += page.rows.map { it.docUuid to it.version }
            cursor = page.nextCursor
        } while (cursor != null)
        return all.sortedWith(compareBy({ it.first }, { it.second }))
    }

    private fun summaryCount(
        filter: SubmissionFilter,
        now: Instant,
    ) = SubmissionSummaryQuery(replica(), source)
        .summary(SubmissionSummaryRequest(tenant, holder, entityRef, periodRef, filter, now), deadline)
        .shouldBeInstanceOf<SubmissionSummaryResult.Totals>()
        .totals.rowCount

    private fun exportCount(
        filter: SubmissionFilter,
        now: Instant,
    ) = ExportSubmissions(replica(), source, { _, _ -> "M00000000X" }, clock)
        .handle(ExportSubmissionsRequest(tenant, holder, entityRef, periodRef, filter, now), deadline)
        .shouldBeInstanceOf<ExportSubmissionsResult.Csv>()
        .rowCount

    private fun bucket(b: ReconciliationBucket) = SubmissionFilter(bucket = b)

    @Test
    fun `INV-AC-22763 strictly after the deadline the class holds every version row of X and Y`() {
        listed(bucket(ReconciliationBucket.SENT_NOT_ACKNOWLEDGED), later) shouldBe
            listOf("X" to 1, "X" to 2, "Y" to 1, "Y" to 2)
    }

    @Test
    fun `INV-AC-22763 at the equal instant the class is empty`() {
        listed(bucket(ReconciliationBucket.SENT_NOT_ACKNOWLEDGED), due) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22763 Z and R are never in the class and W is acknowledged not unacknowledged`() {
        listOf(due, later).forEach { now ->
            val notAcked = listed(bucket(ReconciliationBucket.SENT_NOT_ACKNOWLEDGED), now).map { it.first }
            notAcked.none { it == "Z" || it == "R" || it == "W" } shouldBe true
            listed(bucket(ReconciliationBucket.SENT_ACKNOWLEDGED), now) shouldBe listOf("W" to 1, "W" to 2)
            listed(bucket(ReconciliationBucket.REJECTED), now).map { it.first }.distinct() shouldBe listOf("R")
        }
    }

    @Test
    fun `INV-AC-22763 the summary and the export count the rows of the list for each bucket and instant`() {
        listOf(due, later).forEach { now ->
            ReconciliationBucket.entries.forEach { b ->
                val expected = listed(bucket(b), now).size
                summaryCount(bucket(b), now) shouldBe expected
                exportCount(bucket(b), now) shouldBe expected
            }
        }
        summaryCount(bucket(ReconciliationBucket.SENT_NOT_ACKNOWLEDGED), later) shouldBe 4
        exportCount(bucket(ReconciliationBucket.SENT_NOT_ACKNOWLEDGED), later) shouldBe 4
    }

    @Test
    fun `INV-AC-22763 every bucket is served and none answers BUCKET_NOT_SERVED`() {
        SERVED_BUCKETS shouldBe ReconciliationBucket.entries.toSet()
    }
}
