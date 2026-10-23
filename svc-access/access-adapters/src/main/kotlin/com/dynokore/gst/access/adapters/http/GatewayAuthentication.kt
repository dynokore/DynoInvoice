package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.principal.AuthenticationDecision
import com.dynokore.gst.access.application.principal.PrincipalAuthenticator
import com.dynokore.gst.access.application.principal.PrincipalTenantResolver
import com.dynokore.gst.access.domain.principal.CredentialMethod
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext

/**
 * The adapter of `authenticatePrincipal` (INV-Q-8982 A, ARC 4.3 step 1a): the request carries no tenant, so the tenant
 * of the presented reference is resolved by the claim port under the platform binding, bound with the actor of the
 * gateway call, and only then does the use case run. An unknown reference is REFUSED with UNKNOWN and the use case
 * does not run.
 */
class GatewayAuthentication(
    private val resolver: PrincipalTenantResolver,
    private val platform: PlatformTenantBinding,
    private val tenants: TenantContext,
    private val authenticator: PrincipalAuthenticator,
    private val actor: String,
) {
    init {
        require(actor.isNotBlank()) { "the actor of the gateway call must be set" }
    }

    fun authenticate(
        principalRef: String,
        method: CredentialMethod,
        proof: String,
    ): AuthenticationDecision {
        val tenant =
            platform.bindPlatform(actor) { resolver.tenantOf(principalRef) }
                ?: return AuthenticationDecision.Refused(UNKNOWN)
        return tenants.bind(tenant, actor) { authenticator.handle(principalRef, method, proof) }
    }

    private companion object {
        const val UNKNOWN = "UNKNOWN"
    }
}
