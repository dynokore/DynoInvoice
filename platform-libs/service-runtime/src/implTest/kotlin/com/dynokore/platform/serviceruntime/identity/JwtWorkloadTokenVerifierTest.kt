package com.dynokore.platform.serviceruntime.identity

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date

/** STK-26, INV-AC-2200: signature, issuer and expiry checked before a workload token is trusted. */
class JwtWorkloadTokenVerifierTest {
    private fun rsaKeyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun signedToken(
        privateKey: RSAPrivateKey,
        expiresAt: Instant = Instant.now().plusSeconds(300),
        subject: String = "canonical-svc",
    ): String {
        val claims = JWTClaimsSet.Builder()
            .subject(subject)
            .issuer("https://kubernetes.default.svc")
            .expirationTime(Date.from(expiresAt))
            .issueTime(Date.from(Instant.now()))
            .build()
        val jwt = SignedJWT(JWSHeader(JWSAlgorithm.RS256), claims)
        jwt.sign(RSASSASigner(privateKey))
        return jwt.serialize()
    }

    @Test
    fun `a validly-signed, unexpired token is verified with its subject`() {
        val keyPair = rsaKeyPair()
        val decoder = NimbusJwtDecoder.withPublicKey(keyPair.public as RSAPublicKey).build()
        val verifier = JwtWorkloadTokenVerifier(decoder)

        val result = verifier.verify(WorkloadToken(signedToken(keyPair.private as RSAPrivateKey)))

        result shouldBe WorkloadVerification.Verified("canonical-svc")
    }

    @Test
    fun `a missing token is refused without touching the decoder`() {
        val decoder = NimbusJwtDecoder.withPublicKey(rsaKeyPair().public as RSAPublicKey).build()

        JwtWorkloadTokenVerifier(decoder).verify(null) shouldBe WorkloadVerification.Refused("missing")
    }

    @Test
    fun `an expired token is refused, never the token's own claim in the reason (CS-34)`() {
        val keyPair = rsaKeyPair()
        val decoder = NimbusJwtDecoder.withPublicKey(keyPair.public as RSAPublicKey).build()
        val expired = signedToken(keyPair.private as RSAPrivateKey, expiresAt = Instant.now().minusSeconds(60))

        JwtWorkloadTokenVerifier(decoder).verify(WorkloadToken(expired)) shouldBe WorkloadVerification.Refused("invalid")
    }

    @Test
    fun `a token signed by a different key than the callee trusts is refused`() {
        val trusted = rsaKeyPair()
        val impostor = rsaKeyPair()
        val decoder = NimbusJwtDecoder.withPublicKey(trusted.public as RSAPublicKey).build()
        val tokenFromSomeoneElse = signedToken(impostor.private as RSAPrivateKey)

        JwtWorkloadTokenVerifier(decoder).verify(WorkloadToken(tokenFromSomeoneElse)) shouldBe WorkloadVerification.Refused("invalid")
    }
}
