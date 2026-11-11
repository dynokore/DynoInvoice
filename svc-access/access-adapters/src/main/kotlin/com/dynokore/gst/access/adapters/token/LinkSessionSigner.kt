package com.dynokore.gst.access.adapters.token

import com.dynokore.gst.access.adapters.events.PublishedKey
import com.dynokore.gst.access.application.link.LinkSessionClaims
import com.dynokore.gst.access.application.link.LinkSessionIssuer
import com.dynokore.gst.access.application.token.SigningKeyUnavailable
import com.dynokore.gst.access.application.token.TokenSettings
import com.dynokore.platform.serviceruntime.binding.SecretNotFound
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.binding.SecretStore
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import java.util.Date
import java.util.Locale
import com.dynokore.gst.access.application.link.LinkSessionSigner as LinkSessionSignerPort

/**
 * Stores the public parts of link-session keys and publishes them in `OperatorSessionKeysChanged` in one committed
 * transaction; returns the stored item of each candidate, in order.
 */
fun interface LinkSessionKeyPublication {
    fun publish(keys: List<PublishedKey>): List<PublishedKey>
}

/**
 * The `SecretStore` adapter of the link-session signer port (INV-REQ-899; RFC 8725; NIST SP 800-57 one key, one use).
 * The signing key is an RSA private JWK of at least [MIN_MODULUS_BITS] bits read at every use from [CURRENT_SECRET]
 * (the optional next key from [NEXT_SECRET]); its private part is never stored, returned or logged. Before the first
 * signing with a kid, the public parts of the current and the next key are published (committed) with use
 * `LINK_SESSION`; a key stays published for the rotation interval plus the longest token lifetime, so the old key is
 * still there while a token it signed lives. The token is a compact JWS with `typ` `link-session+jwt`, `alg` RS256 and
 * the `kid`; claims `sub`, `tenant_id`, `iat`, `exp`, `jti` only.
 */
class LinkSessionSigner(
    private val store: SecretStore,
    private val publication: LinkSessionKeyPublication,
    private val settings: TokenSettings,
    private val clock: Clock,
) : LinkSessionSignerPort {
    override fun sign(claims: LinkSessionClaims): String {
        val now = clock.instant()
        val current = open(CURRENT_SECRET) ?: throw SigningKeyUnavailable("the link-session key is unavailable")
        val sessions = listOfNotNull(current, open(NEXT_SECRET))
        val validUntil = now.plus(settings.keyRotationInterval).plus(LinkSessionIssuer.MAX_LIFETIME)
        val stored =
            publication.publish(
                sessions.map { PublishedKey(it.kid, USE, ALG, it.publicKey, now, validUntil) },
            )
        val own = stored.first()
        if (own.kid != current.kid || own.publicKey != current.publicKey) {
            throw SigningKeyUnavailable("the published link-session key differs from the signing key")
        }
        if (!now.isBefore(own.validFrom.plus(settings.keyRotationInterval))) {
            throw SigningKeyUnavailable("the link-session key is past its signing interval")
        }
        return current.sign(claims)
    }

    private fun open(name: String): LinkSigningKey? {
        val text =
            try {
                store.read(SecretRef(name)).reveal()
            } catch (_: SecretNotFound) {
                return null
            } catch (_: Exception) {
                throw SigningKeyUnavailable("a link-session key is unreadable")
            }
        val key =
            try {
                RSAKey.parse(text)
            } catch (_: Exception) {
                throw SigningKeyUnavailable("a link-session key is unreadable")
            }
        if (!key.isPrivate || key.size() < MIN_MODULUS_BITS) {
            throw SigningKeyUnavailable("a link-session key is not an RSA private key of at least 2048 bits")
        }
        return LinkSigningKey(key)
    }

    override fun toString(): String = "LinkSessionSigner(key=<redacted>)"

    companion object {
        const val CURRENT_SECRET = "access/link-session-signing-key"
        const val NEXT_SECRET = "access/link-session-signing-key-next"
        const val USE = "LINK_SESSION"
        const val ALG = "RS256"
        const val TYPE = "link-session+jwt"
        const val MIN_MODULUS_BITS = 2048
    }
}

private class LinkSigningKey(
    private val key: RSAKey,
) {
    private val der: ByteArray = key.toRSAPublicKey().encoded

    val kid: String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(der)
            .take(KID_BYTES)
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
    val publicKey: String = Base64.getEncoder().encodeToString(der)

    fun sign(claims: LinkSessionClaims): String {
        val set =
            JWTClaimsSet
                .Builder()
                .subject(claims.sub)
                .claim("tenant_id", claims.tenantId)
                .issueTime(Date.from(claims.iat))
                .expirationTime(Date.from(claims.exp))
                .jwtID(claims.jti)
                .build()
        val header =
            JWSHeader
                .Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType(LinkSessionSigner.TYPE))
                .keyID(kid)
                .build()
        return SignedJWT(header, set).also { it.sign(RSASSASigner(key)) }.serialize()
    }

    override fun toString(): String = "LinkSigningKey(kid=$kid, key=<redacted>)"

    private companion object {
        const val KID_BYTES = 8
    }
}
