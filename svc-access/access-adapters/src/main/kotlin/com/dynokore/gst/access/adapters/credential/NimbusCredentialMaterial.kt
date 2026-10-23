package com.dynokore.gst.access.adapters.credential

import com.dynokore.gst.access.application.principal.CredentialMaterial
import com.dynokore.gst.access.domain.principal.Credential
import com.dynokore.gst.access.domain.principal.CredentialMethod
import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import java.security.Key
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.HexFormat

/**
 * The proofs of the three credential methods (INV-REQ-880). A client secret is 256 random bits and only its SHA-256
 * is kept; a private_key_jwt proof is a signed client assertion whose `sub` is the principal and whose `exp` has not
 * passed, checked against the stored public key; an MTLS proof is the certificate thumbprint. All comparisons are in
 * constant time. A proof of any other shape, or any exception while checking one, is no proof.
 */
class NimbusCredentialMaterial(
    private val random: SecureRandom = SecureRandom(),
) : CredentialMaterial {
    override fun newSecret(): String {
        val bytes = ByteArray(SECRET_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    override fun verifierOf(secret: String): String =
        MessageDigest
            .getInstance(
                "SHA-256",
            ).digest(secret.toByteArray(Charsets.UTF_8))
            .let { HexFormat.of().formatHex(it) }

    override fun isPublicJwk(json: String): Boolean =
        runCatching {
            val jwk = JWK.parse(json)
            !jwk.isPrivate && publicKeyOf(jwk) != null
        }.getOrDefault(false)

    override fun proves(
        credential: Credential,
        principalRef: String,
        proof: String,
        at: Instant,
    ): Boolean =
        runCatching {
            when (credential.method) {
                CredentialMethod.CLIENT_SECRET_BASIC -> {
                    sameBytes(verifierOf(proof), checkNotNull(credential.secretVerifier))
                }

                CredentialMethod.MTLS -> {
                    sameBytes(proof, checkNotNull(credential.certificateThumbprint))
                }

                CredentialMethod.PRIVATE_KEY_JWT -> {
                    assertionIsValid(checkNotNull(credential.publicKeyJwk), principalRef, proof, at)
                }
            }
        }.getOrDefault(false)

    private fun assertionIsValid(
        jwkJson: String,
        principalRef: String,
        proof: String,
        at: Instant,
    ): Boolean {
        val jwt = SignedJWT.parse(proof)
        val key = publicKeyOf(JWK.parse(jwkJson)) ?: return false
        val verifier = DefaultJWSVerifierFactory().createJWSVerifier(jwt.header, key)
        val claims = jwt.jwtClaimsSet
        val expires = claims.expirationTime?.toInstant()
        return jwt.verify(verifier) && claims.subject == principalRef && expires != null && at.isBefore(expires)
    }

    private fun publicKeyOf(jwk: JWK): Key? =
        when (jwk) {
            is ECKey -> jwk.toECPublicKey()
            is RSAKey -> jwk.toRSAPublicKey()
            else -> null
        }

    private fun sameBytes(
        a: String,
        b: String,
    ): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private companion object {
        const val SECRET_BYTES = 32
    }
}
