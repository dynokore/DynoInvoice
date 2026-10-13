package com.dynokore.platform.serviceruntime.identity

import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.OperatorSessionKey
import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.text.ParseException
import java.time.Clock
import java.time.Duration
import java.util.Base64

/**
 * Verifies a tenant-scoped operator session token (owner ruling INV-Q-4119; platform-contracts access
 * `openOperatorSession`) against access-svc's operator-session keys held by the [authority] replica, never by
 * calling access-svc (coordinator rulings INV-Q-4162, INV-Q-4171). The token is accepted only when:
 *  - its header `kid` names a key the replica holds and is valid now (no valid key: every such token is refused);
 *  - its header `alg` is the key's own algorithm, an RSA or EC signature algorithm (never HMAC or `none`);
 *  - the signature verifies with that key;
 *  - it carries `iat` and `exp`, `exp` at most [MAX_LIFETIME] after `iat` (API-23), and is not expired or
 *    issued in the future (both beyond [CLOCK_SKEW], the same tolerance as the identity provider's tokens);
 *  - its `act` is an object with a `role` and a `case` as openOperatorSession's `caseId` allows (coordinator ruling
 *    INV-Q-4529): 1 to 64 code points, no ':' and no ISO control character (U+0000-U+001F, U+007F-U+009F), and
 *    at least one outside the caseId whitespace set (coordinator ruling INV-Q-4534); the rule is
 *    [OperatorSession.isValidCase], and a token whose case breaks it is refused (null, 401). A ':' would make `case <case>` and `case <case>: <own reason>` ambiguous, and a
 *    control character would forge structure in an audit reason. The `role` may not hold a control character either.
 * The caller checks `sub` and `tenant_id`. Anything else is refused (null, 401).
 */
internal class OperatorSessionTokens(
    private val authority: AuthorityReplica,
    private val clock: Clock,
) {
    /** The verified claims and the session, or null when the token is refused. */
    fun verify(jwt: SignedJWT): Pair<JWTClaimsSet, OperatorSession>? {
        val key = jwt.header.keyID?.let(authority.operatorSessionKeys::valid) ?: return null
        if (jwt.header.algorithm?.name != key.alg) return null
        val verifier = verifierFor(jwt.header, key) ?: return null
        val signed =
            try {
                jwt.verify(verifier)
            } catch (invalid: JOSEException) {
                false
            }
        val claims = if (signed) claimsOf(jwt) else null
        return claims?.takeIf(::withinLifetime)?.let { verified -> sessionOf(verified)?.let { verified to it } }
    }

    private fun verifierFor(
        header: JWSHeader,
        key: OperatorSessionKey,
    ): JWSVerifier? {
        val algorithm = header.algorithm
        val family =
            when (algorithm) {
                in JWSAlgorithm.Family.RSA -> "RSA"
                in JWSAlgorithm.Family.EC -> "EC"
                else -> return null
            }
        return try {
            val spki = X509EncodedKeySpec(Base64.getDecoder().decode(key.publicKey))
            DefaultJWSVerifierFactory().createJWSVerifier(header, KeyFactory.getInstance(family).generatePublic(spki))
        } catch (malformed: IllegalArgumentException) {
            null
        } catch (unusable: GeneralSecurityException) {
            null
        } catch (unusable: JOSEException) {
            null
        }
    }

    private fun claimsOf(jwt: SignedJWT): JWTClaimsSet? = try {
        jwt.jwtClaimsSet
    } catch (malformed: ParseException) {
        null
    }

    private fun withinLifetime(claims: JWTClaimsSet): Boolean {
        val issued = claims.issueTime?.toInstant() ?: return false
        val expires = claims.expirationTime?.toInstant() ?: return false
        val notBefore = claims.notBeforeTime?.toInstant()
        val now = clock.instant()
        return expires.isAfter(issued) &&
            !Duration.between(issued, expires).minus(MAX_LIFETIME).isPositive &&
            now.isBefore(expires.plus(CLOCK_SKEW)) &&
            !issued.isAfter(now.plus(CLOCK_SKEW)) &&
            (notBefore == null || !notBefore.isAfter(now.plus(CLOCK_SKEW)))
    }

    private fun sessionOf(claims: JWTClaimsSet): OperatorSession? {
        val act =
            try {
                claims.getJSONObjectClaim(ACT_CLAIM)
            } catch (notAnObject: ParseException) {
                null
            }
        val role = (act?.get(ROLE) as? String)?.takeIf(OperatorSession::isValidRole)
        val case = (act?.get(CASE) as? String)?.takeIf(OperatorSession::isValidCase)
        return if (role != null && case != null) OperatorSession(role, case) else null
    }

    companion object {
        /** The actor claim (RFC 8693) that marks an operator session token. */
        const val ACT_CLAIM = "act"
        private const val ROLE = "role"
        private const val CASE = "case"

        /** API-23, owner ruling INV-Q-4119: an operator session lasts at most 15 minutes. */
        val MAX_LIFETIME: Duration = Duration.ofMinutes(15)

        /** The clock skew Spring's JwtTimestampValidator allows the identity provider's tokens by default. */
        val CLOCK_SKEW: Duration = Duration.ofSeconds(60)
    }
}
