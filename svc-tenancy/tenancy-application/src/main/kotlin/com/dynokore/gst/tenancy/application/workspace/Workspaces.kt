package com.dynokore.gst.tenancy.application.workspace

import com.dynokore.gst.tenancy.application.structure.ChangeType
import com.dynokore.gst.tenancy.application.structure.StructureChangePublisher
import com.dynokore.gst.tenancy.application.tenant.TenantCreation
import com.dynokore.gst.tenancy.application.tenant.TenantRecord
import com.dynokore.gst.tenancy.application.tenant.TenantRefused
import com.dynokore.gst.tenancy.application.tenant.WorkspaceArchival
import com.dynokore.gst.tenancy.application.tenant.WorkspaceStore
import com.dynokore.gst.tenancy.application.tenant.tenantChange
import com.dynokore.gst.tenancy.domain.Environment
import com.dynokore.gst.tenancy.domain.TenantRefs
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner

/** What createWorkspace answered. */
sealed interface WorkspaceCreation {
    /** The SANDBOX sibling, new or (on a repeat of the same production tenant and name) the original. */
    data class Done(
        val workspace: TenantRecord,
        val repeated: Boolean,
    ) : WorkspaceCreation

    data object Conflict : WorkspaceCreation
}

/**
 * createWorkspace (scope tenant, INV-REQ-1125): under the production tenant's own binding, writes the SANDBOX sibling
 * through the definer function (INV-Q-7468 A). The sibling's ref and id come from [TenantRefs] only; its legal name and
 * plan are the production tenant's. The SANDBOX environment is what binds it to the IRAS sandbox endpoint and the AP
 * test credentials (INV-REQ-1125 behavior 2, enforced by [LoadCredential]).
 */
class CreateWorkspace(
    private val tenants: TenantContext,
    private val tx: TransactionRunner,
    private val store: WorkspaceStore,
    private val publisher: StructureChangePublisher,
) {
    fun handle(
        production: String,
        name: String,
    ): WorkspaceCreation =
        tx.inTransaction {
            val named = TenantRefs.idOf(production)
            // a caller bound to another tenant than the one it names is refused before anything is read or written
            if (tenants.current() !=
                named
            ) {
                throw TenantRefused(invalid = false, "the caller is not bound to $production")
            }
            val row = checkNotNull(store.current()) { "the bound tenant has no row" }
            if (row.environment !=
                Environment.PRODUCTION
            ) {
                throw TenantRefused(invalid = true, "a workspace has no workspace")
            }
            val ref = TenantRefs.refOfWorkspace(row.ref, name)
            val sibling =
                TenantRecord(
                    TenantRefs.idOf(ref).value,
                    ref,
                    row.legalName,
                    row.planRef,
                    ref,
                    environment = Environment.SANDBOX,
                    pairedProductionTenant = row.tenantId,
                    workspaceName = name,
                )
            when (val outcome = store.create(sibling)) {
                is TenantCreation.Created -> {
                    publisher.publish(tenantChange(outcome.tenant, ChangeType.CREATED))
                    WorkspaceCreation.Done(outcome.tenant, repeated = false)
                }

                is TenantCreation.Repeated -> {
                    WorkspaceCreation.Done(outcome.tenant, repeated = true)
                }

                TenantCreation.Conflict -> {
                    WorkspaceCreation.Conflict
                }
            }
        }
}

/** archiveWorkspace (scope tenant): undoes createWorkspace; a repeat is a no-op (contract repeatKey workspaceRef). */
class ArchiveWorkspace(
    private val tx: TransactionRunner,
    private val store: WorkspaceStore,
    private val publisher: StructureChangePublisher,
) {
    fun handle(workspaceRef: String): WorkspaceArchival =
        tx.inTransaction {
            val archived = store.archiveWorkspace(workspaceRef)
            val row = archived.tenant
            if (archived.outcome == WorkspaceArchival.ARCHIVED && row != null) {
                publisher.publish(tenantChange(row, ChangeType.RETIRED))
            }
            archived.outcome
        }
}
