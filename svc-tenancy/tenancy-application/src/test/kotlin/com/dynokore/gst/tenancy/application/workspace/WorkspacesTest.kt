package com.dynokore.gst.tenancy.application.workspace

import com.dynokore.gst.tenancy.application.tenant.TenantRecord
import com.dynokore.gst.tenancy.application.tenant.TenantRefused
import com.dynokore.gst.tenancy.application.tenant.WorkspaceArchival
import com.dynokore.gst.tenancy.domain.Environment
import com.dynokore.gst.tenancy.domain.TenantRefs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkspacesTest {
    private val production = "acme"
    private val productionId = TenantRefs.idOf(production)
    private val context = InMemoryTenantContext(productionId)
    private val store =
        InMemoryWorkspaceStore(context, TenantRecord(productionId.value, production, "Acme", "plan", "c-1"))
    private val create = CreateWorkspace(context, DirectTransactions, store)

    @Test
    fun `the sibling takes its id and ref only from TenantRefs and pairs to the production tenant`() {
        val done = create.handle(production, "uat") as WorkspaceCreation.Done
        val ref = TenantRefs.refOfWorkspace(production, "uat")
        assertEquals(ref, done.workspace.ref)
        assertEquals(TenantRefs.idOf(ref).value, done.workspace.tenantId)
        assertEquals(productionId.value, done.workspace.pairedProductionTenant)
        assertEquals(Environment.SANDBOX, done.workspace.environment)
        assertFalse(done.repeated)
    }

    @Test
    fun `a repeat returns the original and adds no row, another name adds a sibling`() {
        val first = create.handle(production, "uat") as WorkspaceCreation.Done
        val again = create.handle(production, "uat") as WorkspaceCreation.Done
        assertTrue(again.repeated)
        assertEquals(first.workspace, again.workspace)
        assertEquals(2, store.rows.size)
        create.handle(production, "dry-run")
        assertEquals(3, store.rows.size)
    }

    @Test
    fun `a caller bound to another tenant than the one named is refused and nothing is written`() {
        val refused = assertThrows(TenantRefused::class.java) { create.handle("other", "uat") }
        assertFalse(refused.invalid)
        assertEquals(1, store.rows.size)
    }

    @Test
    fun `archiving twice archives once`() {
        val ref = (create.handle(production, "uat") as WorkspaceCreation.Done).workspace.ref
        val archive = ArchiveWorkspace(DirectTransactions, store)
        assertEquals(WorkspaceArchival.ARCHIVED, archive.handle(ref))
        assertEquals(WorkspaceArchival.ALREADY_ARCHIVED, archive.handle(ref))
        assertEquals(WorkspaceArchival.MISSING, archive.handle("nothing"))
        assertEquals(1, store.rows.count { it.retired })
    }
}
