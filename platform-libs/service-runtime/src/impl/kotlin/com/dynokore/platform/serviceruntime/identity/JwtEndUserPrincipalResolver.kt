package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException

/**
 * The default [EndUserPrincipalResolver] (owner ruling INV-Q-3716, INV-REQ-1202 b4, CS-23). [jwtDecoder]
 * verifies the propagated end-user token against the `IdentityProvider` binding's issuer (signature, issuer,
 * expiry, audience; STK-27). `sub` is the actor and `tenant_id` the tenant, absent for a platform operator,
 * who then has no entities. The entity set is not a claim: it is the entities on which the principal holds a
 * grant in that tenant in the [authority] replica, read at every request, so a revoked grant leaves the set
 * at the next one. A missing, invalid or expired token, or an unreadable `tenant_id`, resolves to null (401); an
 * issuer whose keys cannot be obtained, with no cached key verifying the token, throws
 * [TokenIssuerUnavailableException] (503 SRT-011, INV-Q-3773).
 */
class JwtEndUserPrincipalResolver(
    private val jwtDecoder: JwtDecoder,
    private val authority: AuthorityReplica,
) : EndUserPrincipalResolver {
    override fun resolve(authorization: String?): EndUserPrincipal? {
        val token = bearer(authorization) ?: return null
        val jwt =
            try {
                jwtDecoder.decode(token)
            } catch (invalid: BadJwtException) {
                null
            } catch (unavailable: JwtException) {
                // not a bad token: the issuer's keys could not be obtained and no cached key verified it
                throw TokenIssuerUnavailableException(unavailable)
            }
        val actor = jwt?.subject?.takeIf { it.isNotBlank() }
        val claim = jwt?.getClaimAsString(TENANT_CLAIM)
        return when {
            actor == null -> null
            claim == null -> EndUserPrincipal(actor, null, emptySet())
            else -> tenantOf(claim)?.let { tenant -> principalIn(tenant, actor) }
        }
    }

    /** Within the lag bound the replica's held entities; beyond it none, marked not current (INV-Q-3771). */
    private fun principalIn(
        tenant: TenantId,
        actor: String,
    ): EndUserPrincipal = if (authority.lag().isWithin(authority.lagBound)) {
        EndUserPrincipal(actor, tenant, entitiesOf(tenant, actor))
    } else {
        EndUserPrincipal(actor, tenant, emptySet(), entitySetCurrent = false)
    }

    private fun entitiesOf(
        tenant: TenantId,
        actor: String,
    ): Set<EntityRef> = authority.heldEntities(tenant, actor).mapTo(mutableSetOf()) { EntityRef(it) }

    private fun tenantOf(claim: String): TenantId? = try {
        TenantId.fromClaim(claim)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun bearer(authorization: String?): String? {
        val parts = authorization?.trim()?.split(' ', limit = 2) ?: return null
        val token = parts.getOrNull(1)?.trim()
        return token?.takeIf { parts[0].equals(BEARER, ignoreCase = true) && it.isNotEmpty() }
    }

    companion object {
        /** The tenant claim (INV-Q-3716): the TenantId, absent for a platform operator. */
        const val TENANT_CLAIM = "tenant_id"
        private const val BEARER = "Bearer"
    }
}
