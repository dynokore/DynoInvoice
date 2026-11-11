package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.nimbusds.jose.JOSEException
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.text.ParseException
import java.time.Clock
import java.time.Duration

/**
 * Verifies a link-session token (owner ruling 2026-11-07, option A; INV-T-17803) against the LINK_SESSION keys held
 * by the [authority] replica, never by calling access-svc. Accepted only when: header `typ` is [TYP] (RFC 8725
 * section 3.11); `kid` names a link-session key valid now whose `alg` equals the header `alg` (RS, PS or ES only);
 * the signature verifies; `iat` and `exp` are present, `exp` is after `iat` by at most [MAX_LIFETIME], and the token
 * is neither expired nor issued in the future beyond the 60-second skew of [OperatorSessionTokens]; there is no
 * `act` claim; `sub` matches [SUBJECT]. The caller checks `tenant_id`. Anything else is refused (null, 401).
 */
internal class LinkSessionTokens(
    private val authority: AuthorityReplica,
    private val clock: Clock,
) {
    /** The verified claims, or null when the token is refused. */
    fun verify(jwt: SignedJWT): JWTClaimsSet? {
        if (jwt.header.type?.type != TYP) return null
        val key =
            jwt.header.keyID
                ?.let(authority.linkSessionKeys::valid)
                ?.takeIf { jwt.header.algorithm?.name == it.alg }
        val verifier = key?.let { signatureVerifierFor(jwt.header, it) } ?: return null
        val signed =
            try {
                jwt.verify(verifier)
            } catch (expected: JOSEException) {
                false
            }
        val claims =
            if (signed) {
                try {
                    jwt.jwtClaimsSet
                } catch (malformed: ParseException) {
                    null
                }
            } else {
                null
            }
        return claims?.takeIf {
            !it.claims.containsKey(OperatorSessionTokens.ACT_CLAIM) &&
                SUBJECT.matches(it.subject.orEmpty()) &&
                isLifetimeValid(it)
        }
    }

    private fun isLifetimeValid(claims: JWTClaimsSet): Boolean {
        val issued = claims.issueTime?.toInstant() ?: return false
        val expires = claims.expirationTime?.toInstant() ?: return false
        val now = clock.instant()
        val skew = OperatorSessionTokens.CLOCK_SKEW
        return expires.isAfter(issued) &&
            !Duration.between(issued, expires).minus(MAX_LIFETIME).isPositive &&
            now.isBefore(expires.plus(skew)) &&
            !issued.isAfter(now.plus(skew))
    }

    companion object {
        /** The reserved subject prefix of link principals. */
        const val SUBJECT_PREFIX = "link:"

        /** The explicit type of a link-session token (RFC 8725 section 3.11). */
        const val TYP = "link-session+jwt"

        /** The published form of a linkRef (INV-T-4940) after the prefix. */
        val SUBJECT = Regex("^link:LNK-[A-Za-z0-9_-]{22}$")

        /** The short-lived bound of the option: the 15-minute ceiling of operator sessions (API-23). */
        val MAX_LIFETIME: Duration = OperatorSessionTokens.MAX_LIFETIME
    }
}
