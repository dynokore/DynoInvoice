package com.dynokore.gst.tenancy.application.offboarding

import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.application.workspace.DirectTransactions
import com.dynokore.gst.tenancy.application.workspace.InMemoryTenantContext
import com.dynokore.gst.tenancy.domain.ElectionPermissions
import com.dynokore.gst.tenancy.domain.ExportContent
import com.dynokore.gst.tenancy.domain.ExportManifest
import com.dynokore.gst.tenancy.domain.ExportState
import com.dynokore.gst.tenancy.domain.OffboardingScope
import com.dynokore.gst.tenancy.domain.OffboardingState
import com.dynokore.gst.tenancy.domain.RetentionCustodyHolder
import com.dynokore.gst.tenancy.domain.UnsubmittedOverride
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

/** The offboardTenant use cases over in-memory ports (INV-AC-2056, INV-AC-8104, INV-AC-8177, INV-AC-1051). */
class OffboardTenantTest {
    private val tenantRef = "00000000-0000-0000-0000-0000000000a1"
    private val caller = "identity:USR-004"
    private val entity =
        EntityRecord(UUID.randomUUID(), "E1", "G1", "Acme", "COMPANY", null, null, "ACME", "ACTIVE", false, 1)
    private val holder = RetentionCustodyHolder("Ledger Custody Pte Ltd", "custody@ledger.example")

    /** person -> permission keys, per the catalogue: manage for entity.admin, confirm for the three roles. */
    private val grants =
        mutableMapOf(
            caller to setOf(ElectionPermissions.MANAGE, ElectionPermissions.CONFIRM),
            "identity:USR-0040" to setOf(ElectionPermissions.CONFIRM),
            "identity:USR-FIN" to setOf("finance.view"),
            "identity:USR-TAX" to setOf(ElectionPermissions.CONFIRM),
        )
    private val authorityCalls = mutableListOf<Triple<String, String, String>>()
    private var replicaDown = false
    private val authority =
        ActionAuthority { person, ref, action ->
            authorityCalls += Triple(person, ref, action)
            if (replicaDown) error("replica down")
            action in (grants[person] ?: emptySet())
        }

    private class Store : OffboardingStore {
        val rows = mutableListOf<OffboardingRecord>()

        override fun byIdempotencyKey(key: String) = rows.firstOrNull { it.idempotencyKey == key }

        override fun blocking(
            scope: OffboardingScope,
            entityRef: String?,
        ) = rows.firstOrNull {
            it.scope == scope &&
                it.entityRef == entityRef &&
                it.state != OffboardingState.FAILED
        }

        override fun nextOffboardingRef() = "OFB-${rows.size + 1}"

        override fun insert(record: OffboardingRecord) {
            rows += record
        }
    }

    private val store = Store()
    private var unsubmitted: () -> Int = { 0 }
    private var exportCalls = 0
    private var manifest = ExportManifest("EXP-1", ExportState.READY, ExportContent.entries.toSet())
    private val structure: StructureStore =
        StructureStore::class.java.cast(
            Proxy.newProxyInstance(StructureStore::class.java.classLoader, arrayOf(StructureStore::class.java)) {
                _,
                method,
                args,
                ->
                when (method.name) {
                    "entity" -> entity.takeIf { it.ref == args[0] }
                    else -> error("${method.name} is not used")
                }
            },
        )
    private var port: TenantExport =
        TenantExport {
            exportCalls++
            manifest
        }
    private val tenants = InMemoryTenantContext(TenantId(UUID.fromString(tenantRef)))

    private fun useCase() =
        OffboardTenant(DirectTransactions, structure, store, { _, _ -> unsubmitted() }, port, authority, tenants)

    private fun entityRequest(
        secondBy: String? = "identity:USR-0040",
        holder: RetentionCustodyHolder? = this.holder,
        override: UnsubmittedOverride? = null,
        handover: String? = null,
    ) = OffboardRequest(OffboardingScope.ENTITY, "E1", holder, secondBy, override, null, handover)

    private fun tenantRequest(
        secondBy: String? = null,
        holder: RetentionCustodyHolder? = this.holder,
    ) = OffboardRequest(OffboardingScope.TENANT, null, holder, secondBy)

    private fun person(
        request: OffboardRequest,
        key: String = "k1",
        by: String = caller,
    ) = useCase().byPerson(tenantRef, by, key, request)

    private fun accepted(outcome: OffboardOutcome) =
        assertInstanceOf(OffboardOutcome.Accepted::class.java, outcome).record

    private fun conflict(
        outcome: OffboardOutcome,
        code: String,
    ) {
        assertEquals(code, assertInstanceOf(OffboardOutcome.Conflict::class.java, outcome).code)
        assertTrue(store.rows.isEmpty())
    }

    // ---- custody holder (INV-AC-2056) ----

    @Test
    fun `INV-AC-2056 without a named holder the call is refused 400 and with one it succeeds and stores it`() {
        assertInstanceOf(OffboardOutcome.Invalid::class.java, person(entityRequest(holder = null)))
        assertTrue(store.rows.isEmpty())
        val record = accepted(person(entityRequest(), "k2"))
        assertEquals(holder, record.retentionCustodyHolder)
        assertEquals(holder, store.rows.single().retentionCustodyHolder)
        assertNull(
            store.rows
                .single()
                .retentionCustodyHolder.contactMobile,
        )
    }

    @Test
    fun `INV-AC-2056 a holder with contactMobile is stored and returned unchanged`() {
        val withMobile = holder.copy(contactMobile = "+65 8123 4567")
        val record = accepted(person(entityRequest(holder = withMobile)))
        assertEquals(withMobile, record.retentionCustodyHolder)
        assertEquals(
            "+65 8123 4567",
            store.rows
                .single()
                .retentionCustodyHolder.contactMobile,
        )
    }

    @Test
    fun `INV-AC-2056 a holder whose name is blank after trimming is TNC-009 and nothing is stored`() {
        conflict(person(entityRequest(holder = holder.copy(name = " \t "))), "TNC-009")
    }

    @Test
    fun `INV-AC-2056 a holder whose contactEmail is blank after trimming is TNC-009`() {
        conflict(person(entityRequest(holder = holder.copy(contactEmail = "  "))), "TNC-009")
    }

    @Test
    fun `INV-AC-2056 a request with no retentionCustodyHolder is 400 and not TNC-009`() {
        assertInstanceOf(OffboardOutcome.Invalid::class.java, person(entityRequest(holder = null)))
    }

    @Test
    fun `INV-AC-2056 handoverStatementRef of 200 characters is stored and returned equal`() {
        val ref = "h".repeat(200)
        assertEquals(ref, accepted(person(entityRequest(handover = ref))).handoverStatementRef)
        assertEquals(ref, store.rows.single().handoverStatementRef)
    }

    @Test
    fun `INV-AC-2056 handoverStatementRef of 201 characters is refused`() {
        assertInstanceOf(OffboardOutcome.Invalid::class.java, person(entityRequest(handover = "h".repeat(201))))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `INV-AC-2056 handoverStatementRef absent is stored and returned absent`() {
        assertNull(accepted(person(entityRequest())).handoverStatementRef)
        assertNull(store.rows.single().handoverStatementRef)
    }

    // ---- unsubmitted documents (INV-AC-1051) ----

    @Test
    fun `INV-AC-1051 an unsubmitted in-scope document and no override is TNC-008 and nothing is recorded`() {
        unsubmitted = { 2 }
        conflict(person(entityRequest()), "TNC-008")
        assertEquals(0, exportCalls)
    }

    @Test
    fun `INV-AC-1051 the same with an override succeeds and records the acknowledgement`() {
        unsubmitted = { 2 }
        val override = UnsubmittedOverride("identity:USR-004", "I acknowledge")
        assertEquals(override, accepted(person(entityRequest(override = override))).unsubmittedOverride)
    }

    @Test
    fun `INV-AC-1051 an unreadable count is 422 POL-014 naming the input and nothing is stored`() {
        unsubmitted = { throw UnavailableInput("documents-count") }
        val refused = assertInstanceOf(OffboardOutcome.Refused::class.java, person(entityRequest()))
        assertEquals("documents-count", refused.input)
        assertTrue(store.rows.isEmpty())
    }

    // ---- repeat rules ----

    @Test
    fun `a record REQUESTED or COMPLETED with a new Idempotency-Key is TNC-010 and nothing is created`() {
        accepted(person(entityRequest()))
        val before = store.rows.size
        assertEquals(
            "TNC-010",
            assertInstanceOf(OffboardOutcome.Conflict::class.java, person(entityRequest(), "k2")).code,
        )
        assertEquals(before, store.rows.size)
        store.rows.clear()
        manifest = ExportManifest("EXP-2", ExportState.REQUESTED, emptySet())
        assertEquals(OffboardingState.REQUESTED, accepted(person(entityRequest())).state)
        assertEquals(
            "TNC-010",
            assertInstanceOf(OffboardOutcome.Conflict::class.java, person(entityRequest(), "k3")).code,
        )
        assertEquals(1, store.rows.size)
    }

    @Test
    fun `the same Idempotency-Key replayed returns the original record`() {
        val first = accepted(person(entityRequest()))
        val again = assertInstanceOf(OffboardOutcome.Replayed::class.java, person(entityRequest()))
        assertEquals(first, again.record)
        assertEquals(1, store.rows.size)
        assertEquals(1, exportCalls)
    }

    @Test
    fun `a new key after a FAILED record is a new request with a new offboardingRef`() {
        manifest = ExportManifest("EXP-1", ExportState.FAILED, emptySet())
        val failed = accepted(person(entityRequest()))
        manifest = ExportManifest("EXP-2", ExportState.READY, ExportContent.entries.toSet())
        val second = accepted(person(entityRequest(), "k2"))
        assertEquals(OffboardingState.FAILED, failed.state)
        assertEquals(OffboardingState.COMPLETED, second.state)
        assertTrue(failed.offboardingRef != second.offboardingRef)
    }

    // ---- export (INV-AC-1905 manifest part) ----

    @Test
    fun `INV-AC-1905 a READY manifest with all six members gives record state COMPLETED`() {
        assertEquals(OffboardingState.COMPLETED, accepted(person(entityRequest())).state)
    }

    @Test
    fun `INV-AC-1905 a READY manifest with five members is not COMPLETED`() {
        manifest = ExportManifest("EXP-1", ExportState.READY, ExportContent.entries.drop(1).toSet())
        assertTrue(accepted(person(entityRequest())).state != OffboardingState.COMPLETED)
    }

    @Test
    fun `INV-AC-1905 a FAILED manifest leaves the entity open with a FAILED record`() {
        manifest = ExportManifest("EXP-1", ExportState.FAILED, emptySet())
        assertEquals(OffboardingState.FAILED, accepted(person(entityRequest())).state)
        assertNull(store.blocking(OffboardingScope.ENTITY, "E1"))
    }

    @Test
    fun `the unbound export port is 422 POL-014 naming the input and nothing is stored`() {
        port = UnboundTenantExport("tenant-export")
        val refused = assertInstanceOf(OffboardOutcome.Refused::class.java, person(entityRequest()))
        assertEquals("tenant-export", refused.input)
        assertTrue(store.rows.isEmpty())
    }

    // ---- second confirmation of scope ENTITY (INV-REQ-875, INV-Q-7985 B) ----

    @Test
    fun `INV-AC-2056 secondConfirmationBy equal to the caller is 403 and no record exists`() {
        assertInstanceOf(OffboardOutcome.Forbidden::class.java, person(entityRequest(secondBy = caller)))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `INV-AC-2056 USR-0040 is another person than USR-004 and passes when that person holds the cell`() {
        accepted(person(entityRequest(secondBy = "identity:USR-0040")))
        assertEquals("identity:USR-0040", store.rows.single().secondConfirmationBy)
    }

    @Test
    fun `INV-AC-2056 a named person who holds only finance viewing is 403`() {
        assertInstanceOf(OffboardOutcome.Forbidden::class.java, person(entityRequest(secondBy = "identity:USR-FIN")))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `INV-AC-2056 a named person who holds only the confirm key of tax approver is accepted`() {
        accepted(person(entityRequest(secondBy = "identity:USR-TAX")))
        assertTrue(authorityCalls.any { it == Triple("identity:USR-TAX", "E1", ElectionPermissions.CONFIRM) })
    }

    @Test
    fun `a replica that cannot answer for the confirmer is 403 fail closed`() {
        replicaDown = true
        assertInstanceOf(OffboardOutcome.Forbidden::class.java, person(entityRequest()))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `a caller without the manage key is 403`() {
        assertInstanceOf(OffboardOutcome.Forbidden::class.java, person(entityRequest(), by = "identity:USR-FIN"))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `scope ENTITY with an entityRef and no secondConfirmationBy is refused and stores nothing`() {
        assertInstanceOf(OffboardOutcome.Invalid::class.java, person(entityRequest(secondBy = null)))
        assertTrue(store.rows.isEmpty())
    }

    // ---- scope TENANT (INV-AC-8104, INV-AC-8177) ----

    @Test
    fun `INV-AC-8104 the tenant-scope use case with no secondConfirmationBy completes and stores a record`() {
        val record = accepted(useCase().forWholeTenant(tenantRef, "k1", tenantRequest()))
        assertEquals(OffboardingState.COMPLETED, record.state)
        assertEquals(1, store.rows.size)
        assertTrue(authorityCalls.isEmpty())
    }

    @Test
    fun `INV-AC-8104 the same call with secondConfirmationBy equal to a caller gives the same outcome`() {
        val record = accepted(useCase().forWholeTenant(tenantRef, "k1", tenantRequest(secondBy = caller)))
        assertEquals(OffboardingState.COMPLETED, record.state)
        assertNull(record.secondConfirmationBy)
        assertTrue(authorityCalls.isEmpty())
    }

    @Test
    fun `INV-AC-8104 the same call on behalf of a finance viewer gives the same outcome and reads no role`() {
        grants.clear()
        grants["identity:USR-FIN"] = setOf("finance.view")
        val record = accepted(useCase().forWholeTenant(tenantRef, "k1", tenantRequest(secondBy = "identity:USR-FIN")))
        assertEquals(OffboardingState.COMPLETED, record.state)
        assertTrue(authorityCalls.isEmpty())
    }

    @Test
    fun `INV-AC-8104 the tenant-scope use case still refuses an unsubmitted document and a blank holder`() {
        unsubmitted = { 1 }
        assertEquals(
            "TNC-008",
            assertInstanceOf(
                OffboardOutcome.Conflict::class.java,
                useCase().forWholeTenant(tenantRef, "k1", tenantRequest()),
            ).code,
        )
        unsubmitted = { 0 }
        assertEquals(
            "TNC-009",
            assertInstanceOf(
                OffboardOutcome.Conflict::class.java,
                useCase().forWholeTenant(tenantRef, "k2", tenantRequest(holder = holder.copy(name = " "))),
            ).code,
        )
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `INV-AC-8104 scope ENTITY through the whole-tenant entry and scope TENANT with an entityRef are refused`() {
        assertInstanceOf(
            OffboardOutcome.Invalid::class.java,
            useCase().forWholeTenant(tenantRef, "k1", entityRequest()),
        )
        val withEntity = tenantRequest().copy(entityRef = "E1")
        assertInstanceOf(OffboardOutcome.Invalid::class.java, useCase().forWholeTenant(tenantRef, "k2", withEntity))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `INV-AC-8177 the HTTP operation with scope TENANT is 403 and stores nothing for every caller`() {
        listOf(caller, "identity:USR-0040", "identity:USR-FIN", "service:control-plane-svc").forEachIndexed { i, who ->
            assertInstanceOf(OffboardOutcome.Forbidden::class.java, person(tenantRequest(), "k$i", who))
        }
        assertTrue(store.rows.isEmpty())
        assertEquals(0, exportCalls)
    }

    @Test
    fun `a tenantRef of another tenant is 404`() {
        val other = "00000000-0000-0000-0000-0000000000b2"
        assertInstanceOf(OffboardOutcome.NotFound::class.java, useCase().byPerson(other, caller, "k1", entityRequest()))
        assertTrue(store.rows.isEmpty())
    }
}
