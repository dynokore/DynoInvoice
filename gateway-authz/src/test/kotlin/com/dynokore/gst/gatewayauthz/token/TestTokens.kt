package com.dynokore.gst.gatewayauthz.token

import com.dynokore.gst.gatewayauthz.keys.VerificationKey
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Date

/** Mints API-23 access tokens as access-svc issues them (RFC 9068, coordinator ruling INV-Q-4081). */
class TestTokens(
    val kid: String = "2026-10",
) {
    private val keyPair: KeyPair = newKeyPair()

    /** The getVerificationKeys entry of this signer: the DER X.509 SubjectPublicKeyInfo (INV-Q-4079). */
    val verificationKey: VerificationKey get() = VerificationKey(kid, "ES256", keyPair.public.encoded)

    @Suppress("LongParameterList")
    fun mint(
        now: Instant,
        clientId: String? = "PRN-00088",
        subject: String? = clientId,
        scope: String? = "ingest",
        tenantId: String? = "tenant-1",
        jti: String = "jti-${counter++}",
        issuer: String = ISSUER,
        audience: String = AUDIENCE,
        type: String = "at+jwt",
        issuedAt: Instant? = now,
        lifetime: Duration = Duration.ofMinutes(15),
    ): String {
        val claims =
            JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .subject(subject)
                .jwtID(jti)
                .expirationTime(Date.from(now.plus(lifetime)))
                .apply {
                    issuedAt?.let { issueTime(Date.from(it)) }
                    clientId?.let { claim("client_id", it) }
                    scope?.let { claim("scope", it) }
                    tenantId?.let { claim("tenant_id", it) }
                }.build()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256).keyID(kid).type(JOSEObjectType(type)).build()
        return SignedJWT(header, claims).apply { sign(ECDSASigner(keyPair.private as ECPrivateKey)) }.serialize()
    }

    companion object {
        const val ISSUER = "https://access.invoice.test"
        const val AUDIENCE = "https://api.invoice.test"
        private var counter = 0

        private fun newKeyPair(): KeyPair {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            return generator.generateKeyPair()
        }
    }
}
