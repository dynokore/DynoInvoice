package com.dynokore.gst.gatewayauthz.token

import com.dynokore.gst.gatewayauthz.keys.VerificationKey
import com.nimbusds.jose.JOSEException
import com.nimbusds.jose.JWSVerifier
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.text.ParseException
import java.time.Clock
import java.time.Instant

/**
 * The RFC 9068 checks of API-23's machine access tokens (coordinator ruling INV-Q-4081, INV-API-4001), on the request
 * itself with the cached keys only: header `typ` `at+jwt`; a signature by the getVerificationKeys key matched by `kid`
 * and `alg`, its publicKey the DER X.509 SubjectPublicKeyInfo (coordinator ruling INV-Q-4079); `iss` the configured
 * issuer; `aud` containing the public API; `exp` not passed; `iat` present; `sub` present; `scope` exactly one API-23
 * machine scope (INV-Q-4085 A). Anything else is invalid.
 */
class AccessTokenVerifier(
    private val settings: TokenSettings,
    private val keys: () -> List<VerificationKey>?,
    private val clock: Clock,
) {
    @Volatile
    private var verifiers: Pair<List<VerificationKey>, Map<Pair<String, String>, JWSVerifier>>? = null

    /** The verified claims of [compact], or null when the token is invalid. */
    fun verify(compact: String): VerifiedToken? {
        val jwt = parse(compact)?.takeIf { settings.configured } ?: return null
        val header = jwt.header
        val verifier = header.keyID?.let { verifierFor(it, header.algorithm.name) }
        return when {
            header.type?.type?.lowercase() !in ACCESS_TOKEN_TYPES -> null
            verifier == null || !verifies(jwt, verifier) -> null
            else -> claimsOf(jwt.jwtClaimsSet, clock.instant())
        }
    }

    /** The signed JWT with readable claims, or null when [compact] is not one. */
    private fun parse(compact: String): SignedJWT? = try {
        SignedJWT.parse(compact).also { it.jwtClaimsSet }
    } catch (_: ParseException) {
        null
    }

    private fun verifies(
        jwt: SignedJWT,
        verifier: JWSVerifier,
    ): Boolean = try {
        jwt.verify(verifier)
    } catch (_: JOSEException) {
        false
    }

    private fun claimsOf(
        claims: JWTClaimsSet,
        now: Instant,
    ): VerifiedToken? {
        val expiresAt = claims.expirationTime?.toInstant()
        val issuedAt = claims.issueTime?.toInstant()
        val notBefore = claims.notBeforeTime?.toInstant()
        val subject = claims.subject
        val valid =
            claims.issuer == settings.issuer &&
                settings.audience in claims.audience.orEmpty() &&
                expiresAt != null && now.isBefore(expiresAt) &&
                (notBefore == null || !now.isBefore(notBefore)) &&
                issuedAt != null &&
                !subject.isNullOrEmpty()
        val scopes = stringClaim(claims, "scope")?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
        val machineScope = scopes.singleOrNull()?.takeIf { it in VerifiedToken.MACHINE_SCOPES }
        if (!valid || machineScope == null) return null
        return VerifiedToken(
            clientId = stringClaim(claims, "client_id"),
            subject = subject,
            tokenId = claims.jwtid,
            issuedAt = issuedAt,
            tenantId = stringClaim(claims, "tenant_id"),
            machineScope = machineScope,
        )
    }

    private fun stringClaim(
        claims: JWTClaimsSet,
        name: String,
    ): String? = (claims.getClaim(name) as? String)?.takeIf { it.isNotEmpty() }

    private fun verifierFor(
        kid: String,
        alg: String,
    ): JWSVerifier? {
        val current = keys() ?: return null
        val cached = verifiers?.takeIf { it.first === current }?.second
        val byKey = cached ?: buildVerifiers(current).also { verifiers = current to it }
        return byKey[kid to alg]
    }

    private fun buildVerifiers(keys: List<VerificationKey>): Map<Pair<String, String>, JWSVerifier> {
        val entries = keys.mapNotNull { key -> verifierOf(key)?.let { (key.kid to key.alg) to it } }
        return entries.toMap()
    }

    private fun verifierOf(key: VerificationKey): JWSVerifier? = try {
        val spec = X509EncodedKeySpec(key.publicKey)
        when {
            key.alg.startsWith("RS") || key.alg.startsWith("PS") ->
                RSASSAVerifier(KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey)

            key.alg.startsWith("ES") ->
                ECDSAVerifier(KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey)

            else -> null
        }
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: JOSEException) {
        null
    } catch (_: ClassCastException) {
        null
    }

    private companion object {
        /** RFC 9068 section 4: `at+jwt`, or its full media type. */
        val ACCESS_TOKEN_TYPES = setOf("at+jwt", "application/at+jwt")
    }
}
