package com.dynokore.gst.tenancy.application.election

import com.dynokore.gst.tenancy.application.migration.MigrationCutover
import com.dynokore.gst.tenancy.application.migration.MigrationCutoverStore
import com.dynokore.gst.tenancy.application.status.EntityProfileChange
import com.dynokore.gst.tenancy.application.status.EntityProfileChangePublisher
import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.application.workspace.InMemoryTenantContext
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

/** INV-AC-18860: a later EntityProfileChanged carries the migration cutover of its own entity and no other. */
class MigrationCutoverCarryTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val october = LocalDate.of(2026, 10, 1)
    private val basis = "Legal fees relate to exempt supplies"
    private val entities =
        listOf("E1", "E2").associateWith {
            EntityRecord(UUID.randomUUID(), it, "G1", "Acme $it", "COMPANY", null, null, "ACME", "ACTIVE", false, 1)
        }
    private val exempt = FakeExemptStore()
    private val published = mutableListOf<EntityProfileChange>()
    private val lookups = mutableListOf<String>()

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
                    "entity" -> entities[args[0]]
                    "nextStructureVersion" -> 2L
                    "touchEntity" -> null
                    else -> error("${method.name} is not used")
                }
            },
        )

    /** Holds a cutover for E1 only and records every reference it is asked for. */
    private val cutovers =
        object : MigrationCutoverStore {
            override fun find(entityRef: String): MigrationCutover? {
                lookups += entityRef
                return if (entityRef == "E1") {
                    MigrationCutover(
                        "E1",
                        october,
                        "OtherSolution",
                        "user-9",
                        null,
                        Instant.parse("2026-10-02T00:00:00Z"),
                    )
                } else {
                    null
                }
            }

            override fun insert(
                entityId: UUID,
                cutover: MigrationCutover,
            ): Boolean = error("insert is not used")
        }

    private val tenants = InMemoryTenantContext(tenant)
    private val authority = ActionAuthority { _, _, _ -> true }
    private val clock = Clock.fixed(Instant.parse("2026-11-07T01:00:00Z"), ZoneOffset.UTC)
    private val commands =
        ElectionCommands(
            Direct,
            structure,
            idle(),
            idle(),
            object : EntityProfileChangePublisher {
                override fun publish(change: EntityProfileChange) {
                    published += change
                }
            },
            idle(),
            idle(),
            idle(),
            authority,
            { "user-1" },
            idle(),
            idle(),
            tenants,
            ExemptPurchaseElections(Direct, structure, exempt, authority, { "user-1" }, idle(), tenants, clock),
            exempt,
            cutovers,
            clock,
        )

    private fun record(entityRef: String) =
        assertInstanceOf(
            ExemptPurchaseOutcome.Done::class.java,
            commands.recordExemptPurchase(entityRef, listOf("TX-N33", "TX-ESS"), basis, october),
        )

    private val exemptEntries =
        listOf(
            ProfileEntry("exemptPurchaseCodes", "TX-ESS,TX-N33", october),
            ProfileEntry("exemptPurchaseBasis", basis, october),
        )

    @Test
    fun `INV-AC-18860 the change of an entity with a cutover carries migrationCutoverDate after the exempt entries`() {
        record("E1")
        assertEquals(
            exemptEntries + ProfileEntry("migrationCutoverDate", "2026-10-01", october),
            published.single().node.profile,
        )
        assertEquals(listOf("E1"), lookups)
    }

    @Test
    fun `INV-AC-18860 the change of an entity without a cutover holds no migrationCutoverDate and E1 is not carried`() {
        record("E1")
        record("E2")
        val change = published.last()
        assertEquals("E2", change.entityRef)
        assertEquals(exemptEntries, change.node.profile)
        assertTrue(change.node.profile.none { it.attribute == "migrationCutoverDate" })
        assertEquals(listOf("E1", "E2"), lookups)
    }

    private class FakeExemptStore : ExemptPurchaseElectionStore {
        val rows = mutableListOf<ExemptPurchaseElectionRow>()

        override fun history(entityRef: String) = rows.filter { it.entityRef == entityRef }

        override fun insert(row: ExemptPurchaseElectionRow) {
            rows += row
        }
    }
}
