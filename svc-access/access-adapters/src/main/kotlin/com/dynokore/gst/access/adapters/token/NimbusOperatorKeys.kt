package com.dynokore.gst.access.adapters.token

import com.dynokore.gst.access.application.operatorsession.OperatorKeys
import com.dynokore.gst.access.application.operatorsession.OperatorSessionClaims
import com.dynokore.gst.access.application.operatorsession.OperatorSigningSession
import com.dynokore.gst.access.application.token.SigningKeyUnavailable
import com.dynokore.platform.serviceruntime.binding.SecretNotFound
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
 * The SecretStore adapter of [OperatorKeys] (INV-Q-16473 A). The current key is the bound credential
 * [CURRENT_SECRET], the optional next key [NEXT_SECRET]; both are P-256 private JWKs (RFC 7517) read at every use, so a
 * replaced credential needs no restart. The machine signing credential is never read here. The `kid` is the first 16
 * hexadecimal characters of the SHA-256 of the DER public key. A read or parse failure is [SigningKeyUnavailable] with
 * a fixed text, never the content of the credential.
 */
class NimbusOperatorKeys(
    private val store: SecretStore,
) : OperatorKeys {
    override fun current(): OperatorSigningSession = open(CURRENT_SECRET) ?: throw unreadable()

    override fun next(): OperatorSigningSession? = open(NEXT_SECRET)

    private fun open(name: String): OperatorSigningSession? {
        val text =
            try {
                store.read(SecretRef(name)).reveal()
            } catch (_: SecretNotFound) {
                return null
            } catch (_: Exception) {
                throw unreadable()
            }
        return NimbusOperatorSession(p256(text))
    }

    private fun p256(text: String): ECKey {
        val key =
            try {
                ECKey.parse(text)
            } catch (_: Exception) {
                throw unreadable()
            }
        return key.takeIf { it.isPrivate && it.curve == Curve.P_256 }
            ?: throw SigningKeyUnavailable("an operator-session key is not a P-256 private key")
    }

    private fun unreadable() = SigningKeyUnavailable("an operator-session key is unreadable")

    companion object {
        const val CURRENT_SECRET = "access/operator-session-signing-key"
        const val NEXT_SECRET = "access/operator-session-signing-key-next"
    }
}

private class NimbusOperatorSession(
    private val key: ECKey,
) : OperatorSigningSession {
    private val der: ByteArray = key.toECPublicKey().encoded

    override val kid: String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(der)
            .take(KID_BYTES)
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
    override val alg: String = JWSAlgorithm.ES256.name
    override val publicKey: String = Base64.getEncoder().encodeToString(der)

    override fun sign(claims: OperatorSessionClaims): String {
        val set =
            JWTClaimsSet
                .Builder()
                .issuer(claims.issuer)
                .subject(claims.subject)
                .issueTime(Date.from(claims.issuedAt))
                .expirationTime(Date.from(claims.expiresAt))
                .jwtID(claims.tokenId)
                .claim("tenant_id", claims.tenantId)
                .claim("act", mapOf("role" to claims.role, "case" to claims.case))
                .build()
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.ES256)
                .type(JOSEObjectType.JWT)
                .keyID(kid)
                .build()
        return SignedJWT(header, set).also { it.sign(ECDSASigner(key)) }.serialize()
    }

    override fun toString(): String = "OperatorSigningSession(kid=$kid, key=<redacted>)"

    private companion object {
        const val KID_BYTES = 8
    }
}
