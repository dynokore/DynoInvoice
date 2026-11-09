package com.dynokore.gst.tenancy.application.tenant

import com.dynokore.gst.tenancy.application.structure.ChangeType
import com.dynokore.gst.tenancy.application.structure.NodeType
import com.dynokore.gst.tenancy.application.structure.StructureChange
import com.dynokore.gst.tenancy.application.structure.StructureChangePublisher
import com.dynokore.gst.tenancy.application.structure.tenantNode
import com.dynokore.gst.tenancy.domain.Environment
import com.dynokore.gst.tenancy.domain.TenantState
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.util.UUID

/** One tenant row as the contract's TenantResponse reads it. */
data class TenantRecord(
    val tenantId: UUID,
    val ref: String,
    val legalName: String,
    val planRef: String,
    val contractReference: String,
    val structureVersion: Long = 1,
    val retired: Boolean = false,
    val dataRegion: String? = null,
    /** INV-REQ-1125: a workspace is a tenant with environment SANDBOX. */
    val environment: Environment = Environment.PRODUCTION,
    /** INV-REQ-1125: the production tenant a SANDBOX workspace is paired to; null on a production tenant. */
    val pairedProductionTenant: UUID? = null,
    /** INV-REQ-1125: the workspace's name; null on a production tenant. */
    val workspaceName: String? = null,
    /** INV-Q-8097 B: the stored tenant state text (SCHEDULED, OPEN or CLOSED); compared by equality, never parsed. */
    val state: String = TenantState.SCHEDULED.name,
)

/** What opening a tenant answered. */
sealed interface TenantOpening {
    /** The tenant went from SCHEDULED to OPEN now. */
    data class Opened(
        val tenant: TenantRecord,
    ) : TenantOpening

    /** The tenant was already OPEN: nothing was written. */
    data class Repeated(
        val tenant: TenantRecord,
    ) : TenantOpening

    /** The tenant is CLOSED (or in no openable state): nothing was written. */
    data class Refused(
        val tenant: TenantRecord,
    ) : TenantOpening

    data object Missing : TenantOpening
}

/** What the tenant-creating function answered (INV-Q-7468 A). */
sealed interface TenantCreation {
    data class Created(
        val tenant: TenantRecord,
    ) : TenantCreation

    /** The same tenant and contract again: nothing is written and the original row is returned. */
    data class Repeated(
        val tenant: TenantRecord,
    ) : TenantCreation

    /** Another contract on the tenant, or the contract or ref of another tenant: nothing is written. */
    data object Conflict : TenantCreation
}

/** What archiving a workspace answered. */
enum class WorkspaceArchival { ARCHIVED, ALREADY_ARCHIVED, MISSING }

/**
 * What the port answers on archiving: the [outcome] and the workspace row ([tenant], with its structure version after
 * the call), null when MISSING.
 */
data class WorkspaceArchived(
    val outcome: WorkspaceArchival,
    val tenant: TenantRecord?,
)

/** The binding or the arguments were refused by the function: [invalid] answers the contract's 400, else its 403. */
class TenantRefused(
    val invalid: Boolean,
    message: String,
) : RuntimeException(message)

/** The one route that writes `tenancy.tenant`: a definer function (INV-Q-7468 A). */
interface TenantStore {
    fun create(tenant: TenantRecord): TenantCreation

    /** The bound tenant's own row; row-level security hides every other. */
    fun current(): TenantRecord?

    /** Opens the tenant [ref] (SCHEDULED to OPEN) under the platform binding; no other tenant is touched. */
    fun open(ref: String): TenantOpening

    /** Sets the bound tenant CLOSED (a completed whole-tenant offboarding); a repeat changes nothing. */
    fun close()
}

/** The stored state of the bound tenant, read by the go-live gate only (INV-Q-8097 B). */
fun interface TenantStates {
    fun current(): String?
}

/** The workspace side of the tenant store (INV-REQ-1125); [TenantStore] keeps its members unchanged. */
interface WorkspaceStore : TenantStore {
    /** The workspace [ref] of the bound production tenant, or null when [ref] is no workspace of it. */
    fun workspace(ref: String): TenantRecord?

    /** Archives the workspace [ref] of the bound production tenant; the row carries the new structure version. */
    fun archiveWorkspace(ref: String): WorkspaceArchived
}

/** createTenant (scope platform): runs under the reserved platform binding, never under a tenant's. */
class CreateTenant(
    private val platform: PlatformTenantBinding,
    private val tx: TransactionRunner,
    private val store: TenantStore,
    private val actor: String,
    private val publisher: StructureChangePublisher,
) {
    fun handle(tenant: TenantRecord): TenantCreation =
        platform.bindPlatform(actor) {
            tx.inTransaction {
                store.create(tenant).also { outcome ->
                    if (outcome is TenantCreation.Created) {
                        publisher.publish(
                            tenantChange(outcome.tenant, ChangeType.CREATED),
                        )
                    }
                }
            }
        }
}

/** openTenant (scope platform, caller control-plane-svc): SCHEDULED to OPEN once; a repeat returns OPEN unchanged. */
class OpenTenant(
    private val platform: PlatformTenantBinding,
    private val tx: TransactionRunner,
    private val store: TenantStore,
    private val actor: String,
) {
    fun handle(tenantRef: String): TenantOpening =
        platform.bindPlatform(actor) { tx.inTransaction { store.open(tenantRef) } }
}

/** The event of the tenant's own TENANT node (INV-Q-15377 A): the event's tenant is the tenant the node describes. */
fun tenantChange(
    row: TenantRecord,
    changeType: ChangeType,
): StructureChange =
    StructureChange(row.tenantId, row.ref, NodeType.TENANT, row.ref, changeType, null, null, tenantNode(row))
