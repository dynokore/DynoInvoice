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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The CSV export ties back to the summary and carries the acting user ID (INV-AC-16330). */
class ExportSubmissionsTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC)
    private val deadline = Deadline.after(Duration.ofSeconds(5), clock)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val entityRef = "E1"
    private val periodRef = "P1"
    private val holder = Principal("holder", PrincipalKind.HUMAN)
    private val statusOnly = Principal("status-only", PrincipalKind.HUMAN)

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

    private class CountingGstns(
        private val value: String?,
    ) : EntityGstns {
        var calls = 0

        override fun gstnOf(
            tenant: TenantId,
            entityRef: String,
        ): String? {
            calls++
            return value
        }
    }

    private fun export(
        source: SubmissionRowSource,
        principal: Principal = holder,
        filter: SubmissionFilter = SubmissionFilter(),
        gstns: EntityGstns = CountingGstns("M00000000X"),
    ) = ExportSubmissions(replica(), source, gstns, clock)
        .handle(ExportSubmissionsRequest(tenant, principal, entityRef, periodRef, filter), deadline)

    private fun csvOf(result: ExportSubmissionsResult) = result.shouldBeInstanceOf<ExportSubmissionsResult.Csv>()

    /** The data lines after the header block, the empty line and the column line. */
    private fun dataLines(csv: String) = csv.split("\r\n").drop(4).filter { it.isNotEmpty() }

    @Test
    fun `INV-AC-16330 the three headers equal the summary values for the same filter`() {
        val filters =
            listOf(
                SubmissionFilter(),
                SubmissionFilter(direction = Direction.PURCHASE),
                SubmissionFilter(state = LifecycleState.REJECTED),
                SubmissionFilter(counterparty = "Acme"),
                SubmissionFilter(amountMin = BigDecimal("50.00"), amountMax = BigDecimal("300.00")),
            )
        filters.forEach { filter ->
            val source = RecordingSource(rows)
            val summary =
                SubmissionSummaryQuery(replica(), source)
                    .summary(SubmissionSummaryRequest(tenant, holder, entityRef, periodRef, filter), deadline)
                    .shouldBeInstanceOf<SubmissionSummaryResult.Totals>()
                    .totals
            val csv = csvOf(export(source, filter = filter))
            csv.rowCount shouldBe summary.rowCount
            csv.totalTaxableAmount.compareTo(summary.totalTaxableAmount) shouldBe 0
            csv.totalGstAmount.compareTo(summary.totalGstAmount) shouldBe 0
        }
    }

    @Test
    fun `INV-AC-16330 the CSV data row count equals the row count header`() {
        val csv = csvOf(export(RecordingSource(rows), filter = SubmissionFilter(direction = Direction.SALE)))
        (csv.rowCount > 0) shouldBe true
        dataLines(csv.csv).size shouldBe csv.rowCount
    }

    @Test
    fun `INV-AC-16330 generated-by is the acting user ID`() {
        val csv = csvOf(export(RecordingSource(rows)))
        csv.csv.split("\r\n")[1].split(",")[4] shouldBe holder.id
    }

    @Test
    fun `INV-AC-16330 a counterparty name starting with an equals sign is neutralised`() {
        val evil = rows + row("D10", 1, LifecycleState.REJECTED, Direction.SALE, "=cmd", "1.00")
        val csv = csvOf(export(RecordingSource(evil)))
        csv.csv shouldContain ",'=cmd,"
    }

    @Test
    fun `INV-AC-16330 a caller without doc view content is refused and the source is not called`() {
        val source = RecordingSource(rows)
        export(source, principal = statusOnly) shouldBe ExportSubmissionsResult.Forbidden
        source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-16330 a caller without any grant is refused and the source is not called`() {
        val source = RecordingSource(rows)
        export(source, principal = Principal("stranger", PrincipalKind.HUMAN)) shouldBe
            ExportSubmissionsResult.Forbidden
        source.calls shouldBe 0
    }
}
