package com.dynokore.gst.access.adapters.token

import com.dynokore.gst.access.application.token.AccessTokenClaims
import com.dynokore.gst.access.application.token.AccessTokenSigner
import com.dynokore.gst.access.application.token.SigningKeyUnavailable
import com.dynokore.gst.access.application.token.SigningSession
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.MessageDigest
import java.util.Base64
import java.util.Date
import java.util.Locale

/**
 * The `SecretStore` adapter of [AccessTokenSigner] (STK-29, CS-24). The signing key is read from the secret
 * [SIGNING_KEY_SECRET] at every use, so a replaced secret needs no restart, and is a P-256 private JWK (RFC 7517). The
 * `kid` is the first 16 hexadecimal characters of the SHA-256 of the DER public key. The key never leaves the session:
 * it is not returned, not logged, and redacted in `toString()`. Any read or parse failure is [SigningKeyUnavailable]
 * with a fixed text, never the secret's content.
 */
class NimbusAccessTokenSigner(
    private val secrets: SecretStore,
) : AccessTokenSigner {
    override fun open(): SigningSession {
        val key =
            try {
                ECKey.parse(secrets.read(SecretRef(SIGNING_KEY_SECRET)).reveal())
            } catch (_: Exception) {
                throw SigningKeyUnavailable("the signing key secret is unreadable")
            }
        if (!key.isPrivate || key.curve != Curve.P_256) {
            throw SigningKeyUnavailable("the signing key secret is not a P-256 private key")
        }
        return NimbusSigningSession(key)
    }

    companion object {
        const val SIGNING_KEY_SECRET = "access/token-signing-key"
    }
}

private class NimbusSigningSession(
    private val key: ECKey,
) : SigningSession {
    private val der: ByteArray = key.toECPublicKey().encoded

    override val kid: String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(der)
            .take(KID_BYTES)
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
    override val alg: String = JWSAlgorithm.ES256.name
    override val publicKey: String = Base64.getEncoder().encodeToString(der)

    override fun sign(claims: AccessTokenClaims): String {
        val set =
            JWTClaimsSet
                .Builder()
                .issuer(claims.issuer)
                .subject(claims.subject)
                .audience(listOf(claims.audience))
                .issueTime(Date.from(claims.issuedAt))
                .expirationTime(Date.from(claims.expiresAt))
                .jwtID(claims.tokenId)
                .claim("client_id", claims.subject)
                .claim("scope", claims.scope)
                .claim("principal_scope", claims.principalScope)
                .claim("permissions", claims.permissions.sorted())
                .apply { claims.tenantId?.let { claim("tenant_id", it) } }
                .build()
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.ES256)
                .type(JOSEObjectType("at+jwt"))
                .keyID(kid)
                .build()
        return SignedJWT(header, set).also { it.sign(ECDSASigner(key)) }.serialize()
    }

    override fun toString(): String = "SigningSession(kid=$kid, key=<redacted>)"

    private companion object {
        const val KID_BYTES = 8
    }
}
