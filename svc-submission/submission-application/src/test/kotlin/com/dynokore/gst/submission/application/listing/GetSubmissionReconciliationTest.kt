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

/** The reconciliation counts each document once, with the function and instant of the list (INV-AC-22764). */
class GetSubmissionReconciliationTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC)
    private val deadline = Deadline.after(Duration.ofSeconds(5), clock)
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-00000000000a"))
    private val entityRef = "E1"
    private val periodRef = "P1"
    private val holder = Principal("holder", PrincipalKind.HUMAN)
    private val stranger = Principal("stranger", PrincipalKind.HUMAN)
    private val due = Instant.parse("2026-03-01T10:00:00Z")
    private val later = due.plusSeconds(1)

    private val roleMap =
        OperationRoleMap { key ->
            OperationRoles.rolesFor(DeploymentForm.SAAS, key).mapValues { CellAllowance.valueOf(it.value.name) }
        }

    private fun roleHolding(key: String): String =
        roleMap
            .rolesFor(key)
            .keys
            .sorted()
            .first()

    private fun replica(): AuthorityReplica =
        AuthorityReplica(clock, roleMap).also { replica ->
            replica.onSnapshotComplete(0)
            val role = roleHolding(ListingActions.VIEW_STATUS)
            val grant = RoleGrant(role, GrantScope.ENTITY, entityRef, clock.instant().minusSeconds(60))
            val record = AuthorityRecord.RoleGrantRecord("$role-$entityRef", RoleGrantState.ACTIVE, grant)
            replica.accept(AuthorityChanged(tenant, holder.id, 1, record))
        }

    private class CountingSource(
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
        doc: String,
        version: Int,
        state: LifecycleState,
        stored: Instant?,
        amount: String,
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
        taxableAmount = BigDecimal(amount),
        gstAmount = BigDecimal("9.00"),
        categoryCodes = setOf("SR"),
        submittedAt = null,
        acknowledgedAt = if (state == LifecycleState.ACKNOWLEDGED) Instant.parse("2026-03-01T00:00:00Z") else null,
        acknowledgementId = if (state == LifecycleState.ACKNOWLEDGED) "ACK-$doc-$version" else null,
        originalDueDate = null,
        extendedDueDate = null,
        acknowledgementDeadline = stored,
    )

    /** X and Y: two versions, the amount of the last one counts; Z never sent; W acknowledged then sent; R rejected. */
    private val rows =
        listOf(
            row("X", 1, LifecycleState.SUPERSEDED, Instant.parse("2030-01-01T00:00:00Z"), "1.00"),
            row("X", 2, LifecycleState.SENT, due, "100.10"),
            row("Y", 1, LifecycleState.SUPERSEDED, Instant.parse("2030-01-01T00:00:00Z"), "2.00"),
            row("Y", 2, LifecycleState.SENT_UNCONFIRMED, due, "200.20"),
            row("Z", 1, LifecycleState.QUEUED, null, "300.00"),
            row("W", 1, LifecycleState.ACKNOWLEDGED, null, "4.00"),
            row("W", 2, LifecycleState.SENT, due.minusSeconds(3600), "400.40"),
            row("R", 1, LifecycleState.REJECTED, due, "500.50"),
        )

    private fun request(
        principal: Principal = holder,
        now: Instant = later,
    ) = SubmissionReconciliationRequest(tenant, principal, entityRef, periodRef, now)

    private fun totals(result: SubmissionReconciliationResult) =
        result.shouldBeInstanceOf<SubmissionReconciliationResult.Totals>().totals

    private fun listedDocs(
        source: SubmissionRowSource,
        bucket: ReconciliationBucket,
        now: Instant,
    ): Int {
        val list = ListSubmissions(replica(), source)
        val docs = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val req =
                ListSubmissionsRequest(
                    tenant,
                    holder,
                    entityRef,
                    periodRef,
                    SubmissionFilter(bucket = bucket),
                    null,
                    cursor,
                    2,
                    500,
                    now,
                )
            val page = list.handle(req, deadline).shouldBeInstanceOf<ListSubmissionsResult.Page>()
            docs += page.rows.map { it.docUuid }
            cursor = page.nextCursor
        } while (cursor != null)
        return docs.size
    }

    @Test
    fun `INV-AC-22764 five totals in contract order with class counts and last-version amounts`() {
        val source = CountingSource(rows)
        val result = totals(GetSubmissionReconciliation(replica(), source).handle(request(), deadline))
        result.map { it.bucket } shouldBe ReconciliationBucket.entries
        result.map { it.count } shouldBe listOf(1, 2, 1, 0, 1)
        result.map { it.totalAmount } shouldBe
            listOf("400.40", "300.30", "500.50", "0.00", "300.00").map { BigDecimal(it) }
        result.all { it.totalAmount.scale() == 2 } shouldBe true
        source.calls shouldBe 1
    }

    @Test
    fun `INV-AC-22764 each count equals the distinct documents of the list for the bucket and instant`() {
        listOf(due, later).forEach { now ->
            val source = CountingSource(rows)
            val result = totals(GetSubmissionReconciliation(replica(), source).handle(request(now = now), deadline))
            result.forEach { it.count shouldBe listedDocs(source, it.bucket, now) }
        }
        val atDue =
            totals(GetSubmissionReconciliation(replica(), CountingSource(rows)).handle(request(now = due), deadline))
        atDue.first { it.bucket == ReconciliationBucket.SENT_NOT_ACKNOWLEDGED }.count shouldBe 0
    }

    @Test
    fun `INV-AC-22764 a person is decided by submission-view-status and a machine by machine-reconciliation-read`() {
        fun machineOf(type: String) =
            MachinePrincipalFixtures.machine("m-$type", tenant, MachineEntityCoverage.Entity(entityRef), type)
        val typeMap =
            OperationRoleMap { key ->
                when (key) {
                    ReconciliationActions.READ_MACHINE -> mapOf("machine.reader" to CellAllowance.PERMITTED)
                    ListingActions.VIEW_STATUS_MACHINE -> mapOf("machine.list-only" to CellAllowance.PERMITTED)
                    else -> emptyMap()
                }
            }
        val source = CountingSource(rows)
        val useCase =
            GetSubmissionReconciliation(AuthorityReplica(clock, typeMap).also { it.onSnapshotComplete(0) }, source)
        useCase.handle(request(machineOf("list-only")), deadline) shouldBe SubmissionReconciliationResult.Forbidden
        source.calls shouldBe 0
        totals(useCase.handle(request(machineOf("reader")), deadline)).size shouldBe 5
        source.calls shouldBe 1
        ReconciliationActions.READ_MACHINE shouldBe "machine.reconciliation.read"
        ListingActions.VIEW_STATUS shouldBe "submission.view-status"
        totals(
            GetSubmissionReconciliation(replica(), CountingSource(rows)).handle(request(holder), deadline),
        ).size shouldBe
            5
    }

    @Test
    fun `INV-AC-22764 a denied key is Forbidden and the source is not read`() {
        val source = CountingSource(rows)
        GetSubmissionReconciliation(replica(), source).handle(request(stranger), deadline) shouldBe
            SubmissionReconciliationResult.Forbidden
        source.calls shouldBe 0
    }

    @Test
    fun `INV-AC-22764 no key string contains a role name`() {
        val keys = listOf(ListingActions.VIEW_STATUS, ReconciliationActions.READ_MACHINE)
        val roles = keys.flatMap { roleMap.rolesFor(it).keys }.filter { it.isNotBlank() }
        roles.isNotEmpty() shouldBe true
        keys.forEach { key -> roles.none { key.contains(it, ignoreCase = true) } shouldBe true }
    }
}
