package com.dynokore.platform.tenancykernel

import java.util.UUID

/**
 * The entity set a transaction binds beside its tenant and actor (INV-REQ-866, INV-AC-1912; INV-T-7520, INV-Q-7453):
 * [TransactionRunner] signs it into `app.binding_sig` and `bound_entities()` hands it to the row policies. The runtime
 * supplies it per request; application code never sets it. An empty set is a set that matches no row.
 *
 * Framework-free (ARC-16): this is the `-api` half of the module.
 */
fun interface BoundEntitySource {
    /** The entities the [actor] may reach in [tenant]; empty when it has none or nothing is known. */
    fun entitiesOf(
        tenant: TenantId,
        actor: String,
    ): Set<UUID>
}
