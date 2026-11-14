package com.dynokore.gst.submission.application.listing

import com.dynokore.gst.catalogue.DeploymentForm
import com.dynokore.gst.catalogue.OperationRoles
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

/** The summary and the reconciliation come from the rows, filter and classification of the list (INV-AC-22164). */
class SubmissionSummaryQueryTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC)
    private val deadline = Deadline.after(Duration.ofSeconds(5), clock)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val entityRef = "E1"
    private val periodRef = "P1"
    private val holder = Principal("holder", PrincipalKind.HUMAN)
    private val statusOnly = Principal("status-only", PrincipalKind.HUMAN)
    private val stranger = Principal("stranger", PrincipalKind.HUMAN)

    private val roleMap =
        OperationRoleMap { key ->
            OperationRoles.rolesFor(DeploymentForm.SAAS, key).mapValues { CellAllowance.valueOf(it.value.name) }
        }

    /** A role of the catalogue that holds both scoped keys; never written as a literal (INV-Q-10027). */
    private fun roleHoldingBoth(): String =
        (roleMap.rolesFor(ListingActions.VIEW_STATUS).keys intersect roleMap.rolesFor(ListingActions.VIEW_CONTENT).keys)
            .sorted()
            .first()

    private fun roleStatusOnly(): String =
        (roleMap.rolesFor(ListingActions.VIEW_STATUS).keys - roleMap.rolesFor(ListingActions.VIEW_CONTENT).keys)
            .sorted()
            .first()

    private fun replica(): AuthorityReplica =
        AuthorityReplica(clock, roleMap).also { replica ->
            replica.onSnapshotComplete(0)
            listOf(holder to roleHoldingBoth(), statusOnly to roleStatusOnly()).forEach { (principal, role) ->
                val grant = RoleGrant(role, GrantScope.ENTITY, entityRef, clock.instant().minusSeconds(60))
                val record = AuthorityRecord.RoleGrantRecord("$role-$entityRef", RoleGrantState.ACTIVE, grant)
                replica.accept(AuthorityChanged(tenant, principal.id, 1, record))
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

    @Suppress("LongParameterList") // test helper: one named argument per fixture column
    private fun row(
        doc: String,
        version: Int,
        state: LifecycleState,
        direction: Direction,
        name: String,
        taxable: String,
    ) = SubmissionRow(
        docUuid = doc,
        version = version,
        submissionType = SubmissionKind.TYPE_1A,
        state = state,
        dueDate = LocalDate.of(2026, 11, 10),
        documentNumber = "N-$doc-$version",
        documentTypeCode = "380",
        direction = direction,
        counterpartyName = name,
        counterpartyUen = "UEN-$name",
        transactionDate = LocalDate.of(2026, 11, version),
        taxableAmount = BigDecimal(taxable),
        gstAmount = BigDecimal(taxable).multiply(BigDecimal("0.09")).setScale(2, java.math.RoundingMode.HALF_UP),
        categoryCodes = setOf("SR"),
        submittedAt = null,
        acknowledgedAt = if (state == LifecycleState.ACKNOWLEDGED) Instant.parse("2026-11-03T00:00:00Z") else null,
        acknowledgementId = if (state == LifecycleState.ACKNOWLEDGED) "ACK-$doc-$version" else null,
    )

    /** One document per class, a superseded plus acknowledged pair, and two documents that are in no served class. */
    private val rows =
        listOf(
            row("D1", 1, LifecycleState.SUPERSEDED, Direction.SALE, "Acme", "100.00"),
            row("D1", 2, LifecycleState.ACKNOWLEDGED, Direction.SALE, "Acme", "200.00"),
            row("D2", 1, LifecycleState.REJECTED, Direction.PURCHASE, "Globex", "50.50"),
            row("D3", 1, LifecycleState.QUEUED, Direction.SALE, "Acme", "300.00"),
            row("D4", 1, LifecycleState.DEAD_LETTERED, Direction.SALE, "Initech", "400.10"),
            row("D5", 1, LifecycleState.ACKNOWLEDGED, Direction.PURCHASE, "Globex", "500.00"),
            row("D6", 1, LifecycleState.NOT_SUBMITTABLE, Direction.SALE, "Acme", "7.00"),
            row("D7", 1, LifecycleState.SENT, Direction.SALE, "Acme", "9.00"),
            row("D8", 1, LifecycleState.ACKNOWLEDGED, Direction.SALE, "Acme", "11.11"),
            row("D8", 2, LifecycleState.REJECTED, Direction.SALE, "Acme", "12.00"),
            row("D9", 1, LifecycleState.REJECTED, Direction.SALE, "Acme", "13.00"),
            row("D9", 2, LifecycleState.REJECTED, Direction.PURCHASE, "Globex", "14.00"),
        )

    private fun query(source: SubmissionRowSource = RecordingSource(rows)) = SubmissionSummaryQuery(replica(), source)

    private fun request(
        principal: Principal = holder,
        filter: SubmissionFilter = SubmissionFilter(),
    ) = SubmissionSummaryRequest(tenant, principal, entityRef, periodRef, filter)

    /** Every row the list returns for [filter], read page by page with a page size of two. */
    private fun listed(
        source: SubmissionRowSource,
        filter: SubmissionFilter,
    ): List<ListSubmissionsRow> {
        val list = ListSubmissions(replica(), source)
        val all = mutableListOf<ListSubmissionsRow>()
        var cursor: String? = null
        do {
            val req = ListSubmissionsRequest(tenant, holder, entityRef, periodRef, filter, null, cursor, 2, 500)
            val page = list.handle(req, deadline).shouldBeInstanceOf<ListSubmissionsResult.Page>()
            all += page.rows
            cursor = page.nextCursor
        } while (cursor != null)
        return all
    }

    private fun totals(result: SubmissionSummaryResult) =
        result.shouldBeInstanceOf<SubmissionSummaryResult.Totals>().totals

    private fun sum(
        rows: List<ListSubmissionsRow>,
        pick: (ListSubmissionsRow) -> BigDecimal?,
    ) = rows.fold(BigDecimal.ZERO) { acc, r -> acc.add(pick(r)!!) }

    @Test
    fun `INV-AC-22164 rowCount and totals equal the rows the list returns over all pages for six filters`() {
        val filters =
            listOf(
                SubmissionFilter(direction = Direction.PURCHASE),
                SubmissionFilter(submissionType = SubmissionKind.TYPE_1A),
                SubmissionFilter(state = LifecycleState.REJECTED),
                SubmissionFilter(categoryCode = "SR"),
                SubmissionFilter(counterparty = "Acme"),
                SubmissionFilter(amountMin = BigDecimal("50.00"), amountMax = BigDecimal("300.00")),
            )
        filters.forEach { filter ->
            val source = RecordingSource(rows)
            val summary = totals(query(source).summary(request(filter = filter), deadline))
            val list = listed(source, filter)
            summary.rowCount shouldBe list.size
            summary.totalTaxableAmount.compareTo(sum(list) { it.facts.taxableAmount }) shouldBe 0
            summary.totalGstAmount.compareTo(sum(list) { it.facts.gstAmount }) shouldBe 0
        }
    }

    @Test
    fun `INV-AC-22164 each served bucket counts as many rows as the list under that bucket`() {
        SERVED_BUCKETS.size shouldBe 4
        SERVED_BUCKETS.forEach { bucket ->
            val source = RecordingSource(rows)
            val filter = SubmissionFilter(bucket = bucket)
            val summary = totals(query(source).summary(request(filter = filter), deadline))
            val list = listed(source, filter)
            (list.isNotEmpty()) shouldBe true
            summary.rowCount shouldBe list.size
            summary.totalTaxableAmount.compareTo(sum(list) { it.facts.taxableAmount }) shouldBe 0
        }
    }

    @Test
    fun `INV-AC-22164 a denied caller is Forbidden and the source is not called`() {
        val source = RecordingSource(rows)
        query(source).summary(request(principal = stranger), deadline) shouldBe SubmissionSummaryResult.Forbidden
        source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-22164 a masked-fact filter without doc-view-content is refused and with it answers Totals`() {
        val source = RecordingSource(rows)
        val q = query(source)
        listOf(
            SubmissionFilter(counterparty = "Acme"),
            SubmissionFilter(amountMin = BigDecimal("1.00")),
            SubmissionFilter(amountMax = BigDecimal("1.00")),
            SubmissionFilter(categoryCode = "SR"),
        ).forEach {
            q.summary(request(principal = statusOnly, filter = it), deadline) shouldBe
                SubmissionSummaryResult.MaskedFactFilter
        }
        source.calls shouldBe 0
        totals(q.summary(request(filter = SubmissionFilter(counterparty = "Acme")), deadline))
        source.calls shouldBe 1
    }

    @Test
    fun `INV-AC-22164 the bucket SENT_NOT_ACKNOWLEDGED is refused with zero calls and a denied caller is Forbidden`() {
        val source = RecordingSource(rows)
        val q = query(source)
        val filter = SubmissionFilter(bucket = ReconciliationBucket.SENT_NOT_ACKNOWLEDGED)
        q.summary(request(filter = filter), deadline) shouldBe SubmissionSummaryResult.BucketNotServed
        q.summary(request(principal = stranger, filter = filter), deadline) shouldBe SubmissionSummaryResult.Forbidden
        source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-22164 a MACHINE is decided by machine-status-read and not by the machine key of the list`() {
        fun machineOf(type: String) =
            MachinePrincipalFixtures.machine("m-$type", tenant, MachineEntityCoverage.Entity(entityRef), type)
        val typeMap =
            OperationRoleMap { key ->
                when (key) {
                    ListingActions.VIEW_STATUS_MACHINE -> mapOf("machine.list-only" to CellAllowance.PERMITTED)
                    ListingActions.VIEW_SUMMARY_MACHINE -> mapOf("machine.summary-reader" to CellAllowance.PERMITTED)
                    else -> emptyMap()
                }
            }
        val source = RecordingSource(rows)
        val q = SubmissionSummaryQuery(AuthorityReplica(clock, typeMap).also { it.onSnapshotComplete(0) }, source)
        val listOnly =
            SubmissionSummaryRequest(tenant, machineOf("list-only"), entityRef, periodRef, SubmissionFilter())
        q.summary(listOnly, deadline) shouldBe SubmissionSummaryResult.Forbidden
        source.calls shouldBe 0
        val reader = listOnly.copy(principal = machineOf("summary-reader"))
        totals(q.summary(reader, deadline)).rowCount shouldBe rows.size
    }
}
