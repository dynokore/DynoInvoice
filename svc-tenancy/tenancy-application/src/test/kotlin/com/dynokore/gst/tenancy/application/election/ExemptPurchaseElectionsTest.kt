package com.dynokore.gst.tenancy.application.election

import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.EntityStore
import com.dynokore.gst.tenancy.application.workspace.InMemoryTenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class ExemptPurchaseElectionsTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val now = Instant.parse("2026-11-07T01:00:00Z")
    private val october = LocalDate.of(2026, 10, 1)
    private val codes = listOf("TX-N33", "TX-ESS")
    private val basis = "Legal fees relate to exempt supplies"
    private val u1 = "7b9f0c1e-1111-4111-8111-000000000001"
    private val u2 = "7b9f0c1e-2222-4222-8222-000000000002"

    private val entity =
        EntityRecord(UUID.randomUUID(), "E1", "G1", "Acme", "COMPANY", null, null, "ACME", "ACTIVE", false, 1)
    private val store = FakeStore()
    private val audit = FakeAudit()
    private var actor = u1

    private val service =
        build { person, ref, action ->
            person == u1 && ref == "E1" && action == "entity-config.manage"
        }

    private fun build(authority: ActionAuthority) =
        ExemptPurchaseElections(
            Direct,
            FakeEntities(entity),
            store,
            authority,
            { actor },
            audit,
            InMemoryTenantContext(tenant),
            Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun record(
        by: String = u1,
        taxCodes: List<String> = codes,
        text: String = basis,
        ref: String = "E1",
        from: LocalDate = october,
    ): ExemptPurchaseOutcome {
        actor = by
        return service.record(ref, taxCodes, text, from)
    }

    private fun assertNothingStored(outcome: ExemptPurchaseOutcome) {
        assertEquals(0, store.rows.size)
        assertEquals(0, audit.entries.size)
        assertTrue(outcome !is ExemptPurchaseOutcome.Done)
    }

    @Test
    fun `INV-AC-18132 the first call is Done with one row naming U1 and one audit entry`() {
        val outcome = record()
        val expected = ExemptPurchaseElectionRow(tenant, "E1", october, codes, basis, u1)
        assertEquals(ExemptPurchaseOutcome.Done(expected), outcome)
        assertEquals(listOf(expected), store.rows)
        assertEquals(listOf(ExemptPurchaseAuditRecord("E1", codes, u1, now)), audit.entries)
    }

    @Test
    fun `INV-AC-18132 an identical repeat is Done and stores and audits nothing more`() {
        record()
        val outcome = record()
        assertEquals(true, (outcome as ExemptPurchaseOutcome.Done).repeated)
        assertEquals(1, store.rows.size)
        assertEquals(1, audit.entries.size)
    }

    @Test
    fun `INV-AC-18132 a different value is stored as a new row at once`() {
        record()
        record(taxCodes = listOf("TX-N33"))
        record(taxCodes = listOf("TX-N33"), from = LocalDate.of(2026, 11, 1))
        assertEquals(3, store.rows.size)
        assertEquals(3, audit.entries.size)
    }

    @Test
    fun `INV-AC-18132 U2 without the permission is Forbidden and stores nothing`() {
        val outcome = record(by = u2)
        assertInstanceOf(ExemptPurchaseOutcome.Forbidden::class.java, outcome)
        assertNothingStored(outcome)
    }

    @Test
    fun `INV-AC-18132 a caller holding only a role name is Forbidden`() {
        val outcome = record(by = "entity.admin")
        assertInstanceOf(ExemptPurchaseOutcome.Forbidden::class.java, outcome)
        assertNothingStored(outcome)
    }

    @Test
    fun `INV-AC-18132 an authority that throws is Forbidden`() {
        actor = u1
        val outcome = build { _, _, _ -> error("down") }.record("E1", codes, basis, october)
        assertInstanceOf(ExemptPurchaseOutcome.Forbidden::class.java, outcome)
        assertNothingStored(outcome)
    }

    @Test
    fun `INV-AC-18132 an unknown entity is NotFound and writes nothing`() {
        val outcome = record(ref = "E9")
        assertEquals(ExemptPurchaseOutcome.NotFound, outcome)
        assertNothingStored(outcome)
    }

    @Test
    fun `INV-AC-18132 a blank basis is Invalid and stores nothing`() {
        val outcome = record(text = "   ")
        assertInstanceOf(ExemptPurchaseOutcome.Invalid::class.java, outcome)
        assertNothingStored(outcome)
    }

    @Test
    fun `INV-AC-18132 a lowercase code is Invalid and stores nothing`() {
        val outcome = record(taxCodes = listOf("tx-n33"))
        assertInstanceOf(ExemptPurchaseOutcome.Invalid::class.java, outcome)
        assertNothingStored(outcome)
    }

    @Test
    fun `INV-AC-18132 an empty code list is Invalid and stores nothing`() {
        val outcome = record(taxCodes = emptyList())
        assertInstanceOf(ExemptPurchaseOutcome.Invalid::class.java, outcome)
        assertNothingStored(outcome)
    }

    @Test
    fun `INV-AC-18132 a code with a trailing space is Invalid and stores nothing`() {
        val outcome = record(taxCodes = listOf("TX-N33 "))
        assertInstanceOf(ExemptPurchaseOutcome.Invalid::class.java, outcome)
        assertNothingStored(outcome)
    }

    @Test
    fun `INV-AC-18132 the audit entry never carries a role name as actor`() {
        record()
        assertTrue(audit.entries.none { it.recordedBy == "entity.admin" || it.recordedBy == "tax.approver" })
        assertEquals(u1, audit.entries.single().recordedBy)
    }

    private object Direct : TransactionRunner {
        override fun <T> inTransaction(block: () -> T): T = block()
    }

    private class FakeEntities(
        private val entity: EntityRecord,
    ) : EntityStore {
        override fun entity(ref: String): EntityRecord? = entity.takeIf { it.ref == ref }

        override fun draftEntity(
            groupRef: String,
            legalName: String,
        ): EntityRecord? = error("not used")

        override fun insertEntity(entity: EntityRecord) = error("not used")

        override fun updateEntityCode(
            ref: String,
            code: String,
            structureVersion: Long,
        ) = error("not used")

        override fun touchEntity(
            ref: String,
            structureVersion: Long,
        ) = error("not used")
    }

    private class FakeStore : ExemptPurchaseElectionStore {
        val rows = mutableListOf<ExemptPurchaseElectionRow>()

        override fun history(entityRef: String) = rows.filter { it.entityRef == entityRef }

        override fun insert(row: ExemptPurchaseElectionRow) {
            rows += row
        }
    }

    private class FakeAudit : ElectionAudit {
        val entries = mutableListOf<ExemptPurchaseAuditRecord>()

        override fun requested(change: PendingChange) = error("not used")

        override fun approved(approval: ApprovedChange) = error("not used")

        override fun withdrawn(closure: ClosedChange) = error("not used")

        override fun declined(closure: ClosedChange) = error("not used")

        override fun exemptPurchaseRecorded(record: ExemptPurchaseAuditRecord) {
            entries += record
        }
    }
}
