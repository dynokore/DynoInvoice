package com.dynokore.gst.tenancy.application.election

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
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** INV-AC-18133, application side: what ElectionCommands publishes and the structure read carries for the election. */
class ExemptPurchaseProfileTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val october = LocalDate.of(2026, 10, 1)
    private val basis = "Legal fees relate to exempt supplies"
    private val entity =
        EntityRecord(UUID.randomUUID(), "E1", "G1", "Acme", "COMPANY", null, null, "ACME", "ACTIVE", false, 1)
    private val store = FakeStore()
    private val published = mutableListOf<EntityProfileChange>()
    private var allowed = true

    private object Direct : TransactionRunner {
        override fun <T> inTransaction(block: () -> T): T = block()
    }

    /** A port the scenario reads only through lists: every method answers an empty list or nothing. */
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
                    "entity" -> entity.takeIf { it.ref == args[0] }
                    "nextStructureVersion" -> 2L
                    "touchEntity" -> null
                    else -> error("${method.name} is not used")
                }
            },
        )

    private val tenants = InMemoryTenantContext(tenant)
    private val authority = ActionAuthority { _, _, _ -> allowed }
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
            ExemptPurchaseElections(Direct, structure, store, authority, { "user-1" }, idle(), tenants, clock),
            store,
        )

    private fun record(codes: List<String> = listOf("TX-N33", "TX-ESS")) =
        commands.recordExemptPurchase("E1", codes, basis, october)

    @Test
    fun `INV-AC-18133 the event node profile holds the sorted codes and the basis each from 1 October`() {
        assertInstanceOf(ExemptPurchaseOutcome.Done::class.java, record())
        val change = published.single()
        assertEquals(
            listOf(
                ProfileEntry("exemptPurchaseCodes", "TX-ESS,TX-N33", october),
                ProfileEntry("exemptPurchaseBasis", basis, october),
            ),
            change.node.profile,
        )
        assertEquals(october, change.effectiveDate)
        assertEquals(
            listOf("exemptPurchaseCodes", "exemptPurchaseBasis"),
            change.changedAttributes.map { it.attribute },
        )
        assertEquals(listOf(null, null), change.changedAttributes.map { it.oldValue })
    }

    @Test
    fun `INV-AC-18133 the structure read extras equal the entries of the event`() {
        record()
        val read = ExemptPurchaseProfileExtras(store).entries("E1")
        assertEquals(published.single().node.profile, read)
        assertEquals(emptyList<ProfileEntry>(), ExemptPurchaseProfileExtras(store).entries("E2"))
    }

    @Test
    fun `INV-AC-18133 codes are ordered ascending by code unit and joined by a comma without space`() {
        record(listOf("TX-N33", "TX-ESS", "A1", "9Z"))
        assertEquals(
            "9Z,A1,TX-ESS,TX-N33",
            published
                .single()
                .node.profile
                .first()
                .value,
        )
    }

    @Test
    fun `INV-AC-18133 look-alike attribute names are not the published names`() {
        assertEquals("exemptPurchaseCodes", EXEMPT_PURCHASE_CODES_ATTRIBUTE)
        assertEquals("exemptPurchaseBasis", EXEMPT_PURCHASE_BASIS_ATTRIBUTE)
        record()
        val names =
            published
                .single()
                .node.profile
                .map { it.attribute }
        assertTrue("exemptPurchaseCode" !in names)
        assertTrue("ExemptPurchaseCodes" !in names)
        assertNotEquals("exemptPurchaseCode", EXEMPT_PURCHASE_CODES_ATTRIBUTE)
        assertNotEquals("ExemptPurchaseCodes", EXEMPT_PURCHASE_CODES_ATTRIBUTE)
    }

    @Test
    fun `INV-AC-18133 an identical repeat stores and publishes nothing more`() {
        record()
        val again = record()
        assertTrue((again as ExemptPurchaseOutcome.Done).repeated)
        assertEquals(1, published.size)
        assertEquals(1, store.rows.size)
    }

    @Test
    fun `INV-AC-18133 a later election carries the old codes as oldValue and both entries in the profile`() {
        record()
        commands.recordExemptPurchase("E1", listOf("TX-N33"), "Second basis", LocalDate.of(2026, 11, 1))
        val change = published.last()
        assertEquals(4, change.node.profile.size)
        assertEquals(listOf("TX-ESS,TX-N33", basis), change.changedAttributes.map { it.oldValue })
    }

    @Test
    fun `INV-AC-18133 a refused or forbidden record publishes nothing`() {
        assertInstanceOf(ExemptPurchaseOutcome.Invalid::class.java, record(emptyList()))
        assertInstanceOf(
            ExemptPurchaseOutcome.NotFound::class.java,
            commands.recordExemptPurchase("E9", listOf("TX-N33"), basis, october),
        )
        allowed = false
        assertInstanceOf(ExemptPurchaseOutcome.Forbidden::class.java, record())
        assertEquals(0, published.size)
        assertEquals(0, store.rows.size)
    }

    private class FakeStore : ExemptPurchaseElectionStore {
        val rows = mutableListOf<ExemptPurchaseElectionRow>()

        override fun history(entityRef: String) = rows.filter { it.entityRef == entityRef }

        override fun insert(row: ExemptPurchaseElectionRow) {
            rows += row
        }
    }
}
