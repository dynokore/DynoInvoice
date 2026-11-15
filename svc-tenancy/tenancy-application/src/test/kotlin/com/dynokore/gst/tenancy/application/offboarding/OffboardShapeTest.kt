package com.dynokore.gst.tenancy.application.offboarding

import com.dynokore.gst.tenancy.application.election.ActionAuthority
import com.dynokore.gst.tenancy.application.structure.EntityRecord
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.application.tenant.TenantRecord
import com.dynokore.gst.tenancy.application.workspace.DirectTransactions
import com.dynokore.gst.tenancy.application.workspace.InMemoryTenantContext
import com.dynokore.gst.tenancy.application.workspace.InMemoryWorkspaceStore
import com.dynokore.gst.tenancy.domain.ElectionPermissions
import com.dynokore.gst.tenancy.domain.ExportContent
import com.dynokore.gst.tenancy.domain.ExportManifest
import com.dynokore.gst.tenancy.domain.ExportState
import com.dynokore.gst.tenancy.domain.OffboardingScope
import com.dynokore.gst.tenancy.domain.OffboardingState
import com.dynokore.gst.tenancy.domain.RetentionCustodyHolder
import com.dynokore.gst.tenancy.domain.TenantState
import com.dynokore.gst.tenancy.domain.UnsubmittedOverride
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

/**
 * The shape check of offboardTenant (INV-AC-16048): a value of length zero is Invalid (400 SRT-001) and stores nothing,
 * a value that is non-empty but blank after trimming stays a TNC-009 conflict. Over in-memory ports.
 */
class OffboardShapeTest {
    private val tenantRef = "00000000-0000-0000-0000-0000000000a1"
    private val caller = "identity:USR-004"
    private val entity =
        EntityRecord(UUID.randomUUID(), "E1", "G1", "Acme", "COMPANY", null, null, "ACME", "ACTIVE", false, 1)
    private val holder = RetentionCustodyHolder("Ledger Custody Pte Ltd", "custody@ledger.example")
    private val override = UnsubmittedOverride(caller, "two drafts are abandoned")
    private val grants =
        mapOf(
            caller to setOf(ElectionPermissions.MANAGE, ElectionPermissions.CONFIRM),
            "identity:USR-0040" to setOf(ElectionPermissions.CONFIRM),
        )
    private val authority = ActionAuthority { person, _, action -> action in grants[person].orEmpty() }

    private class Store : OffboardingStore {
        val rows = mutableListOf<OffboardingRecord>()

        override fun byIdempotencyKey(key: String) = rows.firstOrNull { it.idempotencyKey == key }

        override fun blocking(
            scope: OffboardingScope,
            entityRef: String?,
        ) = rows.firstOrNull { it.scope == scope && it.entityRef == entityRef && it.state != OffboardingState.FAILED }

        override fun nextOffboardingRef() = "OFB-${rows.size + 1}"

        override fun insert(record: OffboardingRecord) {
            rows += record
        }
    }

    private val store = Store()
    private var unsubmitted = 0
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
    private val tenants = InMemoryTenantContext(TenantId(UUID.fromString(tenantRef)))
    private val tenantStore =
        InMemoryWorkspaceStore(
            tenants,
            TenantRecord(UUID.fromString(tenantRef), "TEN-A1", "Legal", "PLAN", "C-1", state = TenantState.OPEN.name),
        )

    private fun call(
        holder: RetentionCustodyHolder? = this.holder,
        override: UnsubmittedOverride? = null,
        key: String = "k1",
    ) = OffboardTenant(
        DirectTransactions,
        structure,
        store,
        { _, _ -> unsubmitted },
        { ExportManifest("EXP-1", ExportState.READY, ExportContent.entries) },
        authority,
        tenants,
        tenantStore,
    ).byPerson(
        tenantRef,
        caller,
        key,
        OffboardRequest(OffboardingScope.ENTITY, "E1", holder, "identity:USR-0040", override),
    )

    private fun accepted(outcome: OffboardOutcome) =
        assertInstanceOf(OffboardOutcome.Accepted::class.java, outcome).record

    private fun invalid(outcome: OffboardOutcome) {
        assertInstanceOf(OffboardOutcome.Invalid::class.java, outcome)
        assertTrue(store.rows.isEmpty())
    }

    private fun conflict(
        outcome: OffboardOutcome,
        code: String,
    ) {
        assertEquals(code, assertInstanceOf(OffboardOutcome.Conflict::class.java, outcome).code)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `INV-AC-16048 E1 with unsubmitted documents and no override is TNC-008 and nothing is stored`() {
        unsubmitted = 2
        conflict(call(), "TNC-008")
    }

    @Test
    fun `INV-AC-16048 E1 with an override succeeds and the record holds the caller user ID and the statement`() {
        unsubmitted = 2
        val record = accepted(call(override = override))
        assertEquals(override, record.unsubmittedOverride)
        assertEquals(override, store.rows.single().unsubmittedOverride)
    }

    @Test
    fun `INV-AC-16048 E2 without a holder is refused as Invalid and nothing is stored`() {
        invalid(call(holder = null))
    }

    @Test
    fun `INV-AC-16048 E2 with a holder and mobile stores and returns it unchanged`() {
        val withMobile = holder.copy(contactMobile = "+6591234567")
        assertEquals(withMobile, accepted(call(holder = withMobile)).retentionCustodyHolder)
        assertEquals(withMobile, store.rows.single().retentionCustodyHolder)
    }

    @Test
    fun `INV-AC-16048 E2 with a holder and no mobile returns it without mobile`() {
        assertNull(accepted(call()).retentionCustodyHolder.contactMobile)
        assertNull(
            store.rows
                .single()
                .retentionCustodyHolder.contactMobile,
        )
    }

    @Test
    fun `INV-AC-16048 an empty override statement is Invalid and nothing is stored`() {
        unsubmitted = 2
        invalid(call(override = override.copy(statement = "")))
    }

    @Test
    fun `INV-AC-16048 an empty holder contactEmail is Invalid and nothing is stored`() {
        invalid(call(holder = holder.copy(contactEmail = "")))
    }

    @Test
    fun `INV-AC-16048 an empty override acknowledgedBy is Invalid and nothing is stored`() {
        unsubmitted = 2
        invalid(call(override = override.copy(acknowledgedBy = "")))
    }

    @Test
    fun `INV-AC-16048 an empty holder name is Invalid and nothing is stored`() {
        invalid(call(holder = holder.copy(name = "")))
    }

    @Test
    fun `INV-AC-16048 a holder contactEmail of one space stays TNC-009 and nothing is stored`() {
        conflict(call(holder = holder.copy(contactEmail = " ")), "TNC-009")
    }

    @Test
    fun `INV-AC-16048 an empty override statement is Invalid even when no document is unsubmitted`() {
        invalid(call(override = override.copy(statement = "")))
    }
}
