package com.dynokore.gst.tenancy.application.election

import com.dynokore.gst.tenancy.application.migration.MigrationCutover
import com.dynokore.gst.tenancy.application.migration.MigrationCutoverStore
import com.dynokore.gst.tenancy.application.status.ChangedAttribute
import com.dynokore.gst.tenancy.application.status.EntityProfileChange
import com.dynokore.gst.tenancy.application.status.EntityProfileChangePublisher
import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.application.workspace.InMemoryTenantContext
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-21457: the purchaseDateBasis entry is published on EntityProfileChanged when the election is stored or an
 * approved change is applied (events E1 to E5), over the in-memory ports of the lifecycle test.
 */
class PurchaseDateBasisPublishTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val first = LocalDate.of(2026, 1, 1)
    private val july = LocalDate.of(2026, 7, 1)
    private val now = Instant.parse("2026-06-01T01:00:00Z")
    private val requester = "identity:USR-001"
    private val approver = "identity:USR-002"
    private var current = requester

    private val entity =
        EntityRecord(UUID.randomUUID(), "E-001", "G1", "Acme", "COMPANY", null, null, "C1", "ACTIVE", false, 1)
    private val elections = Elections()
    private val changes = Changes { now }
    private val published = mutableListOf<EntityProfileChange>()

    private object Direct : TransactionRunner {
        override fun <T> inTransaction(block: () -> T): T = block()
    }

    private inline fun <reified T : Any> idle(): T =
        T::class.java.cast(
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
                if (method.returnType == List::class.java) emptyList<Any>() else null
            },
        )

    private val structure: StructureStore =
        StructureStore::class.java.cast(
            Proxy.newProxyInstance(StructureStore::class.java.classLoader, arrayOf(StructureStore::class.java)) {
                _,
                method,
                args,
                ->
                when (method.name) {
                    "entity" -> entity.takeIf { args[0] == it.ref }
                    "nextStructureVersion" -> 2L
                    "touchEntity" -> null
                    else -> error("${method.name} is not used")
                }
            },
        )

    private val authority =
        ActionAuthority { person, ent, action ->
            ent == "E-001" &&
                (
                    (person == requester && action == "entity-config.manage") ||
                        (person == approver && action == "election-change.approve")
                )
        }

    private val exempt =
        object : ExemptPurchaseElectionStore {
            override fun history(entityRef: String) = emptyList<ExemptPurchaseElectionRow>()

            override fun insert(row: ExemptPurchaseElectionRow) = error("not used")
        }

    private val noCutovers =
        object : MigrationCutoverStore {
            override fun find(entityRef: String): MigrationCutover? = null

            override fun insert(
                entityId: UUID,
                cutover: MigrationCutover,
            ): Boolean = error("insert is not used")
        }

    private val audit =
        object : ElectionAudit {
            override fun requested(change: PendingChange) = Unit

            override fun approved(approval: ApprovedChange) = Unit

            override fun withdrawn(closure: ClosedChange) = Unit

            override fun declined(closure: ClosedChange) = Unit

            override fun exemptPurchaseRecorded(record: ExemptPurchaseAuditRecord) = error("not used")
        }

    private val commands =
        ElectionCommands(
            Direct,
            structure,
            elections,
            idle(),
            object : EntityProfileChangePublisher {
                override fun publish(change: EntityProfileChange) {
                    published += change
                }
            },
            object : EntityFactSource {
                override fun recordsPurchases(entityRef: String) = FactAnswer.TRUE

                override fun hasForeignCurrencyDocuments(entityRef: String) = FactAnswer.TRUE

                override fun claimsInputTaxOnPostingBasis(entityRef: String) = FactAnswer.TRUE
            },
            idle(),
            idle(),
            authority,
            { current },
            changes,
            audit,
            InMemoryTenantContext(tenant),
            ExemptPurchaseElections(
                Direct,
                structure,
                exempt,
                authority,
                { current },
                audit,
                InMemoryTenantContext(tenant),
                Clock.fixed(now, ZoneOffset.UTC),
            ),
            exempt,
            noCutovers,
            Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun record(
        election: String,
        on: LocalDate,
    ) = commands.recordElection("E-001", election, on)

    private fun item(
        old: String?,
        new: String,
    ) = ChangedAttribute("purchaseDateBasis", old, new)

    private fun entry(
        value: String,
        from: LocalDate,
    ) = ProfileEntry("purchaseDateBasis", value, from)

    @Test
    fun `INV-AC-21457 E1 the first election publishes one event with oldValue absent`() {
        assertInstanceOf(ElectionOutcome.Done::class.java, record("INVOICE_DATE", first))
        val event = published.single()
        assertEquals(listOf(item(null, "INVOICE_DATE")), event.changedAttributes)
        assertEquals(first, event.effectiveDate)
        assertEquals(listOf(entry("INVOICE_DATE", first)), event.node.profile)
        assertEquals(tenant.value, event.tenant)
        assertEquals("E-001", event.entityRef)
    }

    @Test
    fun `INV-AC-21457 E2 a change to POSTING_DATE becomes PENDING and publishes nothing`() {
        record("INVOICE_DATE", first)
        published.clear()
        assertInstanceOf(ElectionOutcome.Pending::class.java, record("POSTING_DATE", july))
        assertTrue(published.isEmpty())
    }

    @Test
    fun `INV-AC-21457 E3 the approval publishes one event with the old value and both entries`() {
        record("INVOICE_DATE", first)
        val pending = assertInstanceOf(ElectionOutcome.Pending::class.java, record("POSTING_DATE", july)).change
        published.clear()
        current = approver
        assertInstanceOf(
            ElectionOutcome.Approved::class.java,
            commands.approveElectionChange("E-001", pending.changeRef),
        )
        val event = published.single()
        assertEquals(listOf(item("INVOICE_DATE", "POSTING_DATE")), event.changedAttributes)
        assertEquals(july, event.effectiveDate)
        assertEquals(
            listOf(entry("INVOICE_DATE", first), entry("POSTING_DATE", july)),
            event.node.profile,
        )
    }

    @Test
    fun `INV-AC-21457 E4 a repeat of the first election is refused and publishes nothing`() {
        record("INVOICE_DATE", first)
        published.clear()
        val repeat = assertInstanceOf(ElectionOutcome.Done::class.java, record("INVOICE_DATE", first))
        assertTrue(repeat.repeated)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `INV-AC-21457 E5 the apportionment election publishes no purchaseDateBasis item`() {
        record("INVOICE_DATE", first)
        published.clear()
        assertInstanceOf(ElectionOutcome.Done::class.java, record("CLAIMABLE_PORTION", first))
        assertTrue(published.flatMap { it.changedAttributes }.none { it.attribute == "purchaseDateBasis" })
        assertTrue(published.flatMap { it.node.profile }.none { it.attribute == "purchaseDateBasis" })
    }

    @Test
    fun `INV-AC-21457 the attribute name equals the constant and the text purchaseDateBasis`() {
        assertEquals("purchaseDateBasis", PURCHASE_DATE_BASIS_ATTRIBUTE)
    }

    private class Elections : ElectionPolicyStore {
        private val rows = mutableMapOf<ElectionKind, MutableList<ElectionRecord>>()

        override fun history(
            entityRef: String,
            kind: ElectionKind,
        ): List<ElectionRecord> = rows[kind].orEmpty().sortedBy { it.effectiveFrom }

        override fun write(
            entityRef: String,
            groupRef: String,
            kind: ElectionKind,
            value: String,
            basis: String?,
            effectiveFrom: LocalDate,
            actor: String,
            reason: String,
        ) {
            rows.getOrPut(kind) { mutableListOf() } += ElectionRecord(value, basis, effectiveFrom, actor, reason)
        }
    }

    private class Changes(
        private val clock: () -> Instant,
    ) : ElectionChangeStore {
        private val pending = mutableListOf<PendingChange>()

        override fun request(
            entityRef: String,
            kind: ElectionKind,
            value: String,
            basis: String?,
            effectiveFrom: LocalDate,
            requestedBy: String,
        ): PendingChange {
            val change =
                PendingChange(
                    "CHG-${pending.size + 1}",
                    entityRef,
                    kind,
                    value,
                    basis,
                    effectiveFrom,
                    requestedBy,
                    clock(),
                )
            pending += change
            return change
        }

        override fun pending(
            entityRef: String,
            changeRef: String,
        ): PendingChange? = pending.singleOrNull { it.entityRef == entityRef && it.changeRef == changeRef }

        override fun approve(
            entityRef: String,
            changeRef: String,
            approvedBy: String,
        ): ApprovedChange? = pending(entityRef, changeRef)?.let { ApprovedChange(it, approvedBy, clock()) }

        override fun withdraw(
            entityRef: String,
            changeRef: String,
            closedBy: String,
        ): ClosedChange? = error("not used")

        override fun decline(
            entityRef: String,
            changeRef: String,
            closedBy: String,
        ): ClosedChange? = error("not used")

        override fun list(
            entityRef: String,
            limit: Int,
            after: String?,
        ): PendingChangePage = error("not used")
    }
}
