package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.persistence.ExpiryTenantSource
import com.dynokore.gst.masterdata.application.connection.CheckCredentialExpiry
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * The body of the job `credential-expiry-check` (INV-REQ-837): the check of one tenant runs under that tenant's own
 * binding, for every tenant that has a credential with an expiry. A tenant whose check fails is logged and the others
 * still run; the run then fails, so the job run records the failure.
 */
class CheckCredentialExpiryForTenants(
    private val source: ExpiryTenantSource,
    private val tenants: TenantContext,
    private val check: CheckCredentialExpiry,
    private val actor: String,
) {
    /** Returns the number of reminders requested by this run. */
    fun run(): Int {
        var requested = 0
        var failed = 0
        source.tenants().forEach { tenant ->
            try {
                requested += tenants.bind(tenant, actor) { check.run().size }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                LOG.log(System.Logger.Level.ERROR, "credential expiry check failed for tenant ${tenant.value}", e)
                failed++
            }
        }
        check(failed == 0) { "the credential expiry check failed for $failed tenant(s)" }
        return requested
    }

    private companion object {
        private val LOG = System.getLogger(CheckCredentialExpiryForTenants::class.java.name)
    }
}
