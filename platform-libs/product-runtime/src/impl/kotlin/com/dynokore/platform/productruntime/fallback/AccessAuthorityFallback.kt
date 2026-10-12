package com.dynokore.platform.productruntime.fallback

import com.dynokore.gst.productRuntimeAccessClient.adapters.clients.api.AuthoriseApi
import com.dynokore.gst.productRuntimeAccessClient.adapters.clients.model.AuthoriseResponse
import com.dynokore.platform.serviceruntime.replica.AuthorityFallback
import com.dynokore.platform.serviceruntime.replica.AuthorityVerdict
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.FallbackResult
import com.dynokore.platform.serviceruntime.resilience.Deadline

/**
 * The authority replica's fallback (INV-REQ-1201 b3, AD-26): access-svc's `authorise` over the generated client
 * (INV-Q-3852), called only while the replica's lag is beyond its bound. The question is the request's principal,
 * its action as the permission and its entity as the scope (INV-REQ-859); access-svc decides the period and the
 * consent scope item itself, which its contract does not take as parameters. The answer is always 200 with ALLOW or
 * DENY; service-runtime runs the call through the access-svc edge's policy, so any failure is Unavailable and the
 * replica applies its degraded rule.
 */
class AccessAuthorityFallback(
    private val api: AuthoriseApi,
) : AuthorityFallback {
    override fun fetch(
        key: AuthorizationRequest,
        deadline: Deadline,
    ): FallbackResult<AuthorityVerdict> {
        val response = api.authorise(principalRef = key.principal.id, permission = key.action, scope = key.entityRef)
        return FallbackResult.Found(AuthorityVerdict(response.decision == AuthoriseResponse.Decision.ALLOW))
    }
}
