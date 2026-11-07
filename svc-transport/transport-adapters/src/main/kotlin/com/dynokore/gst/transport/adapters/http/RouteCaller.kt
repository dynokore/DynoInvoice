package com.dynokore.gst.transport.adapters.http

import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.TokenIssuerUnavailableException
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/** The end user of a route command: the tenant to bind and the actor to record. */
class RouteCommandCaller(
    val tenant: TenantId,
    val actor: String,
)

/**
 * Who may elect or clear an acknowledgement route (INV-REQ-1310): the end user of the propagated token (API-25), with a
 * TENANT-scope grant of the permission key `vp-49.set`, decided per request by [AuthorityReplica.decideTenant]
 * (`set_permission` of VP-49 in the variation-point catalogue; INV-Q-10027: a permission key, never a role name). A
 * missing token is 401, a token with no tenant, no resolver, no replica or no grant is 403: never let through.
 */
@Component
class RouteCaller(
    private val principals: ObjectProvider<EndUserPrincipalResolver>,
    private val authority: ObjectProvider<AuthorityReplica>,
    private val token: CallerToken,
) {
    fun require(): RouteCommandCaller {
        val principal = principal()
        val tenant = principal.tenant
        val granted =
            tenant != null &&
                authority
                    .getIfAvailable()
                    ?.decideTenant(tenant, Principal(principal.actor, PrincipalKind.HUMAN), SET_ROUTE_PERMISSION)
                    ?.allowed == true
        if (tenant == null || !granted) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        return RouteCommandCaller(tenant, principal.actor)
    }

    private fun principal(): EndUserPrincipal =
        try {
            principals.getIfAvailable()?.resolve(token.authorization())
        } catch (_: TokenIssuerUnavailableException) {
            throw ApiRefusal(ClosedErrorCode.ISSUER_UNAVAILABLE)
        } ?: throw ApiRefusal(ClosedErrorCode.UNAUTHENTICATED)

    companion object {
        /** `set_permission` of VP-49 (INV-NOTE-3 row VP-49), TENANT scope. */
        const val SET_ROUTE_PERMISSION = "vp-49.set"
    }
}
