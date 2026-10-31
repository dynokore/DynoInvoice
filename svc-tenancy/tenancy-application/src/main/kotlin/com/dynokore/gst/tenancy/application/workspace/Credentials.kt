package com.dynokore.gst.tenancy.application.workspace

import com.dynokore.gst.tenancy.application.tenant.TenantStore
import com.dynokore.gst.tenancy.domain.CredentialEnvironmentRule
import com.dynokore.gst.tenancy.domain.CredentialRefusal
import com.dynokore.gst.tenancy.domain.Environment
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner

/** An Access Point credential offered to a tenant or workspace: [name] is a label only, [environmentTag] decides. */
data class AccessPointCredential(
    val name: String,
    val environmentTag: String?,
)

sealed interface CredentialLoad {
    /** [environment] is the environment of the target the credential was accepted by. */
    data class Accepted(
        val environment: Environment,
    ) : CredentialLoad

    data class Refused(
        val reason: CredentialRefusal,
    ) : CredentialLoad
}

/**
 * Holds the credentials of a tenant or workspace once this service has accepted them. The binding itself belongs to
 * the service that owns Access Point connections; the unbound adapter refuses every call until it is bound.
 */
fun interface CredentialBinder {
    fun bind(
        target: TenantId,
        credential: AccessPointCredential,
    )
}

/** Where this service runs: SANDBOX for a private-form sandbox namespace or installation (INV-REQ-1125 behavior 4). */
data class ServiceEnvironment(
    val value: Environment,
)

/**
 * Loading a credential (INV-REQ-1126, INV-REQ-502, INV-REQ-1377): the target is the bound tenant. Its environment is
 * SANDBOX when the tenant row is a SANDBOX workspace (SaaS) or the service itself runs as SANDBOX (private form),
 * else PRODUCTION; the credential is accepted only when its own environment tag equals it, in either direction.
 */
class LoadCredential(
    private val tx: TransactionRunner,
    private val store: TenantStore,
    private val service: ServiceEnvironment,
    private val binder: CredentialBinder,
) {
    fun handle(credential: AccessPointCredential): CredentialLoad =
        tx.inTransaction {
            val tenant = checkNotNull(store.current()) { "the bound tenant has no row" }
            val target =
                if (tenant.environment == Environment.SANDBOX || service.value == Environment.SANDBOX) {
                    Environment.SANDBOX
                } else {
                    Environment.PRODUCTION
                }
            val refusal = CredentialEnvironmentRule.refusal(target, credential.environmentTag)
            if (refusal != null) {
                CredentialLoad.Refused(refusal)
            } else {
                binder.bind(TenantId(tenant.tenantId), credential)
                CredentialLoad.Accepted(target)
            }
        }
}
