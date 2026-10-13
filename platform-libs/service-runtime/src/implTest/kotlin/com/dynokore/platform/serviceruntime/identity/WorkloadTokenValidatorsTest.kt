package com.dynokore.platform.serviceruntime.identity

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date

/**
 * INV-AC-2200: `aud` is checked unconditionally against
 * [WorkloadIdentityProperties.audience] — a wrong or missing audience is
 * refused, never waved through, closing the defect where an unset [audience]
 * skipped the check entirely. Built against a hand-signed key pair
 * ([NimbusJwtDecoder.withPublicKey]), the same way [JwtWorkloadTokenVerifierTest]
 * is, so this exercises [workloadTokenValidators] without a live JWK-set
 * endpoint.
 */
class WorkloadTokenValidatorsTest {
    private fun rsaKeyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun signedToken(
        privateKey: RSAPrivateKey,
        audience: List<String>? = listOf("canonical-svc"),
        subject: String = "calling-svc",
    ): String {
        val claims =
            JWTClaimsSet
                .Builder()
                .subject(subject)
                .issuer("https://kubernetes.default.svc")
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .issueTime(Date.from(Instant.now()))
                .apply { if (audience != null) audience(audience) }
                .build()
        val jwt = SignedJWT(JWSHeader(JWSAlgorithm.RS256), claims)
        jwt.sign(RSASSASigner(privateKey))
        return jwt.serialize()
    }

    private fun verifierFor(
        publicKey: RSAPublicKey,
        configuredAudience: String = "canonical-svc",
    ): WorkloadTokenVerifier {
        val properties = WorkloadIdentityProperties(audience = configuredAudience)
        val decoder = NimbusJwtDecoder.withPublicKey(publicKey).build()
        decoder.setJwtValidator(DelegatingOAuth2TokenValidator(workloadTokenValidators(properties)))
        return JwtWorkloadTokenVerifier(decoder)
    }

    @Test
    fun `a token minted for a different audience is refused, not accepted`() {
        val keyPair = rsaKeyPair()
        val verifier = verifierFor(keyPair.public as RSAPublicKey, configuredAudience = "canonical-svc")
        val wrongAudience = signedToken(keyPair.private as RSAPrivateKey, audience = listOf("someone-elses-svc"))

        verifier.verify(WorkloadToken(wrongAudience)) shouldBe WorkloadVerification.Refused("invalid")
    }

    @Test
    fun `a token with no aud claim at all is refused`() {
        val keyPair = rsaKeyPair()
        val verifier = verifierFor(keyPair.public as RSAPublicKey, configuredAudience = "canonical-svc")
        val noAudience = signedToken(keyPair.private as RSAPrivateKey, audience = null)

        verifier.verify(WorkloadToken(noAudience)) shouldBe WorkloadVerification.Refused("invalid")
    }

    @Test
    fun `a token minted for the callee's own configured audience is accepted`() {
        val keyPair = rsaKeyPair()
        val verifier = verifierFor(keyPair.public as RSAPublicKey, configuredAudience = "canonical-svc")
        val correctAudience = signedToken(keyPair.private as RSAPrivateKey, audience = listOf("canonical-svc"))

        verifier.verify(WorkloadToken(correctAudience)) shouldBe WorkloadVerification.Verified("calling-svc")
    }

    @Test
    fun `building the validators without a configured audience fails fast rather than skipping the check`() {
        val runCatching = runCatching { workloadTokenValidators(WorkloadIdentityProperties(audience = null)) }

        runCatching.isFailure shouldBe true
        (runCatching.exceptionOrNull() is IllegalStateException) shouldBe true
    }
}
