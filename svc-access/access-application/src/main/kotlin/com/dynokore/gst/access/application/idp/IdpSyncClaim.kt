package com.dynokore.gst.access.application.idp

import com.dynokore.platform.tenancykernel.TenantId

/**
 * The tenants the directory-group synchronisation has work for: those holding an ACTIVE group mapping (ARC 4.3 step 1a,
 * INV-Q-8843 A). A claim port over the `SECURITY DEFINER` function `access.claim_idp_sync_tenants()`; it returns the
 * distinct tenants and nothing else, and the scheduler binds each one before it runs the use case.
 */
fun interface IdpSyncClaim {
    fun claimTenants(): List<TenantId>
}
