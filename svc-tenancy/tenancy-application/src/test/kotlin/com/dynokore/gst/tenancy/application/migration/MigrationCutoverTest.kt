package com.dynokore.gst.tenancy.application.migration

import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.status.EntityProfileChange
import com.dynokore.gst.tenancy.application.status.EntityProfileChangePublisher
import com.dynokore.gst.tenancy.application.status.EntityStatusStore
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

/** INV-AC-18811: the cutover use cases at the application layer, over doubles of every port. */
class MigrationCutoverTest {
    private val actor = "identity:USR-009"
    private val date = LocalDate.of(2026, 9, 1)
    private val entity =
        EntityRecord(UUID.randomUUID(), "E1", "G1", "Acme", "COMPANY", null, null, "ACME", "ACTIVE", false, 1)
    private val otherTenantEntity =
        EntityRecord(UUID.randomUUID(), "E9", "G9", "Other", "COMPANY", null, null, "OTH", "ACTIVE", false, 1)

    private class CountingStore : MigrationCutoverStore {
        val rows = mutableMapOf<String, MigrationCutover>()
        var calls = 0

        override fun find(entityRef: String): MigrationCutover? {
            calls++
            return rows[entityRef]
        }

        override fun insert(
            entityId: UUID,
            cutover: MigrationCutover,
        ): Boolean {
            calls++
            return rows.putIfAbsent(cutover.entityRef, cutover) == null
        }
    }

    private object Direct : TransactionRunner {
        override fun <T> inTransaction(block: () -> T): T = block()
    }

    private val store = CountingStore()
    private val published = mutableListOf<EntityProfileChange>()
    private var grants: Set<String> = setOf(MigrationCutoverPermissions.RECORD, MigrationCutoverPermissions.VIEW)
    private var throwing = false
    private val checked = mutableListOf<Triple<String, String, String>>()
    private val authority =
        ActionAuthority { person, ref, action ->
            checked += Triple(person, ref, action)
            if (throwing) error("replica down")
            action in grants
        }

    // tenant A's boundary: the structure store answers only the entities of the bound tenant
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
    private val statuses: EntityStatusStore =
        EntityStatusStore::class.java.cast(
            Proxy.newProxyInstance(EntityStatusStore::class.java.classLoader, arrayOf(EntityStatusStore::class.java)) {
                _,
                method,
                _,
                ->
                if (method.returnType == List::class.java) emptyList<Any>() else error("${method.name} is not used")
            },
        )
    private val clock = Clock.fixed(Instant.parse("2026-11-08T01:00:00Z"), ZoneOffset.UTC)
    private val recorder =
        RecordMigrationCutover(
            Direct,
            structure,
            store,
            statuses,
            object : EntityProfileChangePublisher {
                override fun publish(change: EntityProfileChange) {
                    published += change
                }
            },
            MigrationCutoverProfileExtras(store),
            authority,
            InMemoryTenantContext(TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))),
            clock,
        )
    private val reader = GetMigrationCutover(Direct, structure, store, authority)

    private fun request(
        on: LocalDate = date,
        prior: String = "OldSolution",
        approvedBy: String? = null,
        evidence: String? = null,
    ) = NewMigrationCutover(on, prior, approvedBy, evidence)

    @Test
    fun `INV-AC-18811 a caller without the record key is denied with 0 store calls and no command`() {
        grants = setOf(MigrationCutoverPermissions.VIEW)
        assertEquals(MigrationCutoverOutcome.Denied, recorder.record(actor, "E1", request()))
        assertEquals(0, store.calls)
        assertTrue(published.isEmpty())
        assertEquals(listOf(Triple(actor, "E1", "migration-cutover.record")), checked)
    }

    @Test
    fun `INV-AC-18811 a look-alike key entity-config manage allows neither operation`() {
        grants = setOf("entity-config.manage")
        assertEquals(MigrationCutoverOutcome.Denied, recorder.record(actor, "E1", request()))
        assertEquals(MigrationCutoverOutcome.Denied, reader.get(actor, "E1"))
        assertEquals(0, store.calls)
    }

    @Test
    fun `INV-AC-18811 a role name is not the key and the read asks the view key`() {
        grants = setOf("admin", "approver", "owner")
        assertEquals(MigrationCutoverOutcome.Denied, recorder.record(actor, "E1", request()))
        assertEquals(MigrationCutoverOutcome.Denied, reader.get(actor, "E1"))
        assertEquals("migration-cutover.view", checked.last().third)
    }

    @Test
    fun `INV-AC-18811 an authority that throws is a denial`() {
        throwing = true
        assertEquals(MigrationCutoverOutcome.Denied, recorder.record(actor, "E1", request()))
        assertEquals(MigrationCutoverOutcome.Denied, reader.get(actor, "E1"))
        assertEquals(0, store.calls)
    }

    @Test
    fun `INV-AC-18811 approvedBy is the acting user and a supplied equal value is accepted`() {
        val created = recorder.record(actor, "E1", request()) as MigrationCutoverOutcome.Created
        assertEquals(actor, created.cutover.approvedBy)
        assertEquals(actor, store.rows.getValue("E1").approvedBy)
        val same = recorder.record(actor, "E1", request(approvedBy = actor))
        assertInstanceOf(MigrationCutoverOutcome.Existing::class.java, same)
    }

    @Test
    fun `INV-AC-18811 a differing approvedBy is rejected with 0 store calls`() {
        for (other in listOf("identity:USR-OTHER", "IDENTITY:USR-009", "")) {
            val out = recorder.record(actor, "E1", request(approvedBy = other))
            assertInstanceOf(MigrationCutoverOutcome.Rejected::class.java, out)
        }
        assertEquals(0, store.calls)
        assertTrue(published.isEmpty())
    }

    @Test
    fun `INV-AC-18811 an empty acting user is rejected and nothing is stored`() {
        assertInstanceOf(MigrationCutoverOutcome.Rejected::class.java, recorder.record("", "E1", request()))
        assertEquals(0, store.calls)
    }

    @Test
    fun `INV-AC-18811 the first record stores one row and requests one profile command`() {
        val out = recorder.record(actor, "E1", request(evidence = "EV-1")) as MigrationCutoverOutcome.Created
        val expected = MigrationCutover("E1", date, "OldSolution", actor, "EV-1", Instant.parse("2026-11-08T01:00:00Z"))
        assertEquals(expected, out.cutover)
        assertEquals(mapOf("E1" to expected), store.rows)
        val change = published.single()
        assertEquals("migrationCutoverDate", change.changedAttributes.single().attribute)
        assertEquals("2026-09-01", change.changedAttributes.single().newValue)
        assertEquals(date, change.effectiveDate)
        assertEquals(listOf(ProfileEntry("migrationCutoverDate", "2026-09-01", date)), change.node.profile)
    }

    @Test
    fun `INV-AC-18811 an exact repeat returns the same record with 0 new commands`() {
        val first = recorder.record(actor, "E1", request()) as MigrationCutoverOutcome.Created
        val again = recorder.record(actor, "E1", request())
        assertEquals(MigrationCutoverOutcome.Existing(first.cutover), again)
        assertEquals(1, store.rows.size)
        assertEquals(1, published.size)
    }

    @Test
    fun `INV-AC-18811 a different date is a conflict and the stored row is unchanged`() {
        val first = recorder.record(actor, "E1", request()) as MigrationCutoverOutcome.Created
        val out = recorder.record(actor, "E1", request(on = date.plusDays(1), prior = "Other"))
        assertEquals(MigrationCutoverOutcome.Conflict(first.cutover), out)
        assertEquals(first.cutover, store.rows.getValue("E1"))
        assertEquals(1, published.size)
    }

    @Test
    fun `INV-AC-18811 length rules reject with 0 store calls`() {
        listOf(
            request(prior = ""),
            request(prior = "x".repeat(129)),
            request(evidence = "e".repeat(65)),
        ).forEach { assertInstanceOf(MigrationCutoverOutcome.Rejected::class.java, recorder.record(actor, "E1", it)) }
        assertEquals(0, store.calls)
        assertInstanceOf(
            MigrationCutoverOutcome.Created::class.java,
            recorder.record(actor, "E1", request(prior = "x".repeat(128), evidence = "e".repeat(64))),
        )
    }

    @Test
    fun `INV-AC-18811 the read returns the record, NotFound without one and NotFound across tenants`() {
        assertEquals(MigrationCutoverOutcome.NotFound, reader.get(actor, "E1"))
        val created = recorder.record(actor, "E1", request()) as MigrationCutoverOutcome.Created
        assertEquals(MigrationCutoverOutcome.Existing(created.cutover), reader.get(actor, "E1"))
        store.rows[otherTenantEntity.ref] = created.cutover.copy(entityRef = otherTenantEntity.ref)
        assertEquals(MigrationCutoverOutcome.NotFound, reader.get(actor, otherTenantEntity.ref))
        assertEquals(MigrationCutoverOutcome.NotFound, recorder.record(actor, otherTenantEntity.ref, request()))
    }
}
