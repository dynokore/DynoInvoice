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

    private fun replica(map: OperationRoleMap = roleMap): AuthorityReplica =
        AuthorityReplica(clock, map).also { replica ->
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
        .handle(ExportSubmissionsRequest(tenant, principal, entityRef, periodRef, filter, clock.instant()), deadline)

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
                    .summary(
                        SubmissionSummaryRequest(tenant, holder, entityRef, periodRef, filter, clock.instant()),
                        deadline,
                    ).shouldBeInstanceOf<SubmissionSummaryResult.Totals>()
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
    fun `INV-AC-16330 the header GSTN equals the stored GSTN of the entity`() {
        val csv = csvOf(export(RecordingSource(rows), gstns = CountingGstns("M12345678X")))
        csv.csv.split("\r\n")[1].split(",")[1] shouldBe "M12345678X"
    }

    private val maskedIdx = listOf(2, 6, 7, 9, 10)

    private fun cols(line: String) = line.split(",")

    /** True per data line when the five masked columns are empty. */
    private fun masked(lines: List<String>) = lines.map { l -> maskedIdx.all { cols(l)[it].isEmpty() } }

    private fun filled(lines: List<String>) = lines.map { l -> maskedIdx.all { cols(l)[it].isNotEmpty() } }

    private fun unmaskedPart(lines: List<String>) = lines.map { l -> cols(l).filterIndexed { i, _ -> i !in maskedIdx } }

    /** A role map that records each key asked and grants the roles of [granted] per key. */
    private class RecordingMap(
        granted: Map<String, Set<String>>,
    ) {
        val asked = mutableListOf<String>()
        val map =
            OperationRoleMap { key ->
                asked.add(key)
                granted[key].orEmpty().associateWith { CellAllowance.PERMITTED }
            }
    }

    private val typeGrants =
        mapOf(
            ListingActions.VIEW_STATUS_MACHINE to setOf("machine.M1", "machine.M3"),
            ListingActions.VIEW_STATUS to setOf("machine.M2"),
            ListingActions.VIEW_CONTENT to setOf("machine.M1", "machine.M2"),
        )

    private fun machine(type: String) =
        MachinePrincipalFixtures.machine("m-$type", tenant, MachineEntityCoverage.Entity(entityRef), type)

    private fun exportAs(
        principal: Principal,
        source: SubmissionRowSource,
        gstns: EntityGstns = CountingGstns("M00000000X"),
        filter: SubmissionFilter = SubmissionFilter(),
        map: OperationRoleMap = RecordingMap(typeGrants).map,
    ) = ExportSubmissions(AuthorityReplica(clock, map).also { it.onSnapshotComplete(0) }, source, gstns, clock)
        .handle(ExportSubmissionsRequest(tenant, principal, entityRef, periodRef, filter, clock.instant()), deadline)

    @Test
    fun `INV-AC-23395 a machine granted machine-submissions-read and content gets the filled Csv`() {
        val source = RecordingSource(rows)
        val csv = csvOf(exportAs(machine("M1"), source))
        source.calls shouldBe 1
        filled(dataLines(csv.csv)).forEach { it shouldBe true }
    }

    @Test
    fun `INV-AC-23395 b a machine without machine-submissions-read is Forbidden and nothing is called`() {
        val source = RecordingSource(rows)
        val gstns = CountingGstns("M00000000X")
        exportAs(machine("M2"), source, gstns) shouldBe ExportSubmissionsResult.Forbidden
        source.calls shouldBe 0
        gstns.calls shouldBe 0
    }

    @Test
    fun `INV-AC-23395 c a machine without content gets the masked Csv equal to the full one elsewhere`() {
        val full = csvOf(exportAs(machine("M1"), RecordingSource(rows)))
        val source = RecordingSource(rows)
        val csv = csvOf(exportAs(machine("M3"), source))
        source.calls shouldBe 1
        masked(dataLines(csv.csv)).forEach { it shouldBe true }
        unmaskedPart(dataLines(csv.csv)) shouldBe unmaskedPart(dataLines(full.csv))
        csv.csv
            .split("\r\n")[1]
            .split(",")
            .drop(6) shouldBe
            full.csv
                .split("\r\n")[1]
                .split(",")
                .drop(6)
        csv.rowCount shouldBe full.rowCount
        csv.totalTaxableAmount shouldBe full.totalTaxableAmount
        csv.totalGstAmount shouldBe full.totalGstAmount
    }

    @Test
    fun `INV-AC-23395 c2 a filter on a masked fact is refused for a machine without content and direction is served`() {
        listOf(
            SubmissionFilter(counterparty = "Acme"),
            SubmissionFilter(amountMin = BigDecimal("1.00")),
            SubmissionFilter(amountMax = BigDecimal("1000.00")),
            SubmissionFilter(categoryCode = "SR"),
        ).forEach { filter ->
            val source = RecordingSource(rows)
            val gstns = CountingGstns("M00000000X")
            exportAs(machine("M3"), source, gstns, filter) shouldBe ExportSubmissionsResult.MaskedFactFilter
            source.calls shouldBe 0
            gstns.calls shouldBe 0
        }
        csvOf(exportAs(machine("M3"), RecordingSource(rows), filter = SubmissionFilter(direction = Direction.SALE)))
    }

    @Test
    fun `INV-AC-23395 d a person holding both keys gets the unmasked Csv`() {
        filled(dataLines(csvOf(export(RecordingSource(rows))).csv)).forEach { it shouldBe true }
    }

    @Test
    fun `INV-AC-23395 e the keys asked are exactly the expected lists in order`() {
        fun asked(
            principal: Principal,
            grants: Map<String, Set<String>>,
        ): List<String> {
            val map = RecordingMap(grants)
            exportAs(principal, RecordingSource(rows), map = map.map)
            return map.asked
        }
        asked(machine("M1"), typeGrants) shouldBe listOf("machine.submissions.read", "doc.view.content")
        asked(machine("M2"), typeGrants) shouldBe listOf("machine.submissions.read")
        val recorded = mutableListOf<String>()
        val recordingCatalogue =
            OperationRoleMap { key ->
                recorded.add(key)
                roleMap.rolesFor(key)
            }
        ExportSubmissions(replica(recordingCatalogue), RecordingSource(rows), CountingGstns("M00000000X"), clock)
            .handle(
                ExportSubmissionsRequest(tenant, holder, entityRef, periodRef, SubmissionFilter(), clock.instant()),
                deadline,
            )
        recorded shouldBe listOf("submission.view-status", "doc.view.content")
        recorded.clear()
        ExportSubmissions(replica(recordingCatalogue), RecordingSource(rows), CountingGstns("M00000000X"), clock)
            .handle(
                ExportSubmissionsRequest(tenant, statusOnly, entityRef, periodRef, SubmissionFilter(), clock.instant()),
                deadline,
            )
        recorded shouldBe listOf("submission.view-status", "doc.view.content")
    }

    @Test
    fun `INV-AC-23395 f the machine status key is the published string`() {
        ListingActions.VIEW_STATUS_MACHINE shouldBe "machine.submissions.read"
    }

    @Test
    fun `INV-AC-23395 c3 a person without doc view content gets the masked Csv and the source is read once`() {
        val source = RecordingSource(rows)
        val csv = csvOf(export(source, principal = statusOnly))
        source.calls shouldBe 1
        masked(dataLines(csv.csv)).forEach { it shouldBe true }
    }

    @Test
    fun `INV-AC-16330 a caller without submission view status is Forbidden and nothing is called`() {
        val source = RecordingSource(rows)
        val gstns = CountingGstns("M00000000X")
        val stranger = Principal("stranger", PrincipalKind.HUMAN)
        export(source, principal = stranger, gstns = gstns) shouldBe ExportSubmissionsResult.Forbidden
        source.calls shouldBe 0
        gstns.calls shouldBe 0
    }

    @Test
    fun `INV-AC-16330 a null or blank GSTN gives GstnUnavailable and the source is not called`() {
        listOf(null, "", "   ").forEach { stored ->
            val source = RecordingSource(rows)
            export(source, gstns = CountingGstns(stored)) shouldBe ExportSubmissionsResult.GstnUnavailable
            source.calls shouldBe 0
        }
    }
}
