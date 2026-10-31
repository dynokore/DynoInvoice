package com.dynokore.gst.tenancy.application.workspace

import com.dynokore.gst.tenancy.application.tenant.TenantCreation
import com.dynokore.gst.tenancy.application.tenant.TenantRecord
import com.dynokore.gst.tenancy.application.tenant.WorkspaceArchival
import com.dynokore.gst.tenancy.application.tenant.WorkspaceStore
import com.dynokore.gst.tenancy.domain.SampleRow
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner

/** A bound tenant held in memory. */
class InMemoryTenantContext(
    var tenant: TenantId,
) : TenantContext {
    override fun current(): TenantId = tenant

    override fun currentActor(): String = "actor"

    override fun <T> bind(
        tenant: TenantId,
        actor: String,
        block: () -> T,
    ): T = block()
}

object DirectTransactions : TransactionRunner {
    override fun <T> inTransaction(block: () -> T): T = block()
}

/** Rows keyed by tenant ref; the bound tenant's row is [current]. */
class InMemoryWorkspaceStore(
    private val context: InMemoryTenantContext,
    vararg seed: TenantRecord,
) : WorkspaceStore {
    val rows = seed.toMutableList()

    override fun create(tenant: TenantRecord): TenantCreation {
        val same = rows.firstOrNull { it.ref == tenant.ref }
        if (same != null) return TenantCreation.Repeated(same)
        rows += tenant
        return TenantCreation.Created(tenant)
    }

    override fun current(): TenantRecord? = rows.firstOrNull { it.tenantId == context.tenant.value }

    override fun workspace(ref: String): TenantRecord? =
        rows.firstOrNull { it.ref == ref && it.pairedProductionTenant == context.tenant.value }

    override fun archiveWorkspace(ref: String): WorkspaceArchival {
        val i = rows.indexOfFirst { it.ref == ref && it.pairedProductionTenant == context.tenant.value }
        return when {
            i < 0 -> {
                WorkspaceArchival.MISSING
            }

            rows[i].retired -> {
                WorkspaceArchival.ALREADY_ARCHIVED
            }

            else -> {
                rows[i] = rows[i].copy(retired = true)
                WorkspaceArchival.ARCHIVED
            }
        }
    }
}

class CountingBinder : CredentialBinder {
    val bound = mutableListOf<Pair<TenantId, AccessPointCredential>>()

    override fun bind(
        target: TenantId,
        credential: AccessPointCredential,
    ) {
        bound += target to credential
    }
}

class InMemoryApprovals : SampleApprovals {
    val open = mutableListOf<Pair<String, String>>()

    override fun record(
        sampleRef: String,
        approvedBy: String,
    ) {
        open += sampleRef to approvedBy
    }

    override fun open(sampleRef: String): String? = open.firstOrNull { it.first == sampleRef }?.second

    override fun spend(sampleRef: String): Boolean = open.removeIf { it.first == sampleRef }
}

/** Grants by (person, key); a key outside [SampleCopyPermissions] is unknown and refused with an exception. */
class InMemoryAuthority(
    private val grants: Set<Pair<String, String>>,
) : TenantAuthority {
    override fun holds(
        person: String,
        permission: String,
    ): Boolean {
        require(permission == SampleCopyPermissions.APPROVE || permission == SampleCopyPermissions.REQUEST) {
            "unknown permission key $permission"
        }
        return (person to permission) in grants
    }
}

class RecordingTransfer(
    rows: List<SampleRow>,
) {
    val copied = mutableListOf<Triple<TenantId, String, List<SampleRow>>>()
    val transfer =
        SampleTransfer(
            SampleRowSource { rows },
            SampleCopyPort { workspace, ref, out -> copied += Triple(workspace, ref, out) },
        )
}
