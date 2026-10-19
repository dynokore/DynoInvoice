package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.BlankText
import com.dynokore.platform.tenancykernel.TenantId
import com.nimbusds.jwt.JWT
import com.nimbusds.jwt.JWTParser
import com.nimbusds.jwt.SignedJWT
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import java.text.ParseException
import java.time.Clock

/**
 * The default [EndUserPrincipalResolver] (owner ruling INV-Q-3716, INV-REQ-1202 b4, CS-23). [jwtDecoder]
 * verifies the propagated end-user token against the `IdentityProvider` binding's issuer (signature, issuer,
 * expiry, audience; STK-27). `sub` is the actor and `tenant_id` the tenant, absent for a platform operator,
 * who then has no entities. The entity set is not a claim: it is the entities on which the principal holds a
 * grant in that tenant in the [authority] replica, read at every request, so a revoked grant leaves the set
 * at the next one. A missing, invalid or expired token, a `sub` that is not a valid actor (`BlankText.isActor`,
 * INV-Q-4684), or an unreadable `tenant_id`, resolves to null (401); an
 * issuer whose keys cannot be obtained, with no cached key verifying the token, throws
 * [TokenIssuerUnavailableException] (503 SRT-011, INV-Q-3773).
 *
 * A token carrying `act` is a tenant-scoped operator session (owner ruling INV-Q-4119, INV-REQ-1202 b4): it is
 * verified only against access-svc's operator-session keys held by the [authority] replica ([OperatorSessionTokens],
 * coordinator rulings INV-Q-4162, INV-Q-4171), never against the identity provider, and must carry `sub` and a
 * `tenant_id`; it resolves to a tenant principal with its [OperatorSession]. Every other token is verified by
 * [jwtDecoder] only, so a token from an operator-session key without `act` fails there, and an identity-provider
 * token with `act` is refused. Each refusal is null (401).
 */
class JwtEndUserPrincipalResolver(
    private val jwtDecoder: JwtDecoder,
    private val authority: AuthorityReplica,
    clock: Clock = Clock.systemUTC(),
) : EndUserPrincipalResolver {
    private val operatorSessions = OperatorSessionTokens(authority, clock)

    override fun resolve(authorization: String?): EndUserPrincipal? {
        val token = bearer(authorization)
        val parsed = token?.let(::parseOrNull)
        return when {
            token == null || parsed == null -> null
            carriesAct(parsed) -> operatorPrincipal(parsed)
            else -> identityProviderPrincipal(token)
        }
    }

    private fun parseOrNull(token: String): JWT? =
        try {
            JWTParser.parse(token)
        } catch (malformed: ParseException) {
            null
        }

    /** The unverified claims name `act`: only the operator-session keys may verify it. Unreadable claims: none. */
    private fun carriesAct(parsed: JWT): Boolean =
        try {
            parsed.jwtClaimsSet?.claims?.containsKey(OperatorSessionTokens.ACT_CLAIM) == true
        } catch (malformed: ParseException) {
            false
        }

    private fun operatorPrincipal(parsed: JWT): EndUserPrincipal? {
        val (claims, session) = (parsed as? SignedJWT)?.let(operatorSessions::verify) ?: return null
        val actor = claims.subject?.takeIf(BlankText::isActor)
        val tenant = (claims.getClaim(TENANT_CLAIM) as? String)?.let(::tenantOf)
        return if (actor != null && tenant != null) principalIn(tenant, actor, session) else null
    }

    private fun identityProviderPrincipal(token: String): EndUserPrincipal? {
        val jwt =
            try {
                jwtDecoder.decode(token)
            } catch (expected: BadJwtException) {
                null
            } catch (unavailable: JwtException) {
                // not a bad token: the issuer's keys could not be obtained and no cached key verified it
                throw TokenIssuerUnavailableException(unavailable)
            }
        val actor = jwt?.subject?.takeIf(BlankText::isActor)
        val claim = jwt?.getClaimAsString(TENANT_CLAIM)
        // an identity-provider token never carries `act` (owner ruling INV-Q-4119)
        val act = jwt?.hasClaim(OperatorSessionTokens.ACT_CLAIM) == true
        return when {
            actor == null || act -> null
            claim == null -> EndUserPrincipal(actor, null, emptySet())
            else -> tenantOf(claim)?.let { tenant -> principalIn(tenant, actor) }
        }
    }

    /** Within the lag bound the replica's held entities; beyond it none, marked not current (INV-Q-3771). */
    private fun principalIn(
        tenant: TenantId,
        actor: String,
        session: OperatorSession? = null,
    ): EndUserPrincipal =
        if (authority.lag().isWithin(authority.lagBound)) {
            EndUserPrincipal(actor, tenant, entitiesOf(tenant, actor), operatorSession = session)
        } else {
            EndUserPrincipal(actor, tenant, emptySet(), entitySetCurrent = false, operatorSession = session)
        }

    private fun entitiesOf(
        tenant: TenantId,
        actor: String,
    ): Set<EntityRef> = authority.heldEntities(tenant, actor).mapTo(mutableSetOf()) { EntityRef(it) }

    private fun tenantOf(claim: String): TenantId? =
        try {
            TenantId.fromClaim(claim)
        } catch (expected: IllegalArgumentException) {
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
