package com.dynokore.platform.crypto

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

class BouncyCastleCmsTest {
    private val cms = BouncyCastleCms()
    private val alg = SignatureAlgorithm.ECDSA_P256_SHA256
    private val content = """{"bundle":"configuration","version":"7"}""".toByteArray()

    @Test
    fun `a detached signature verifies against its pinned certificate`() {
        val key = signingKey("secp256r1")
        val other = signingKey("secp256r1")
        val signature = cms.sign(content, key)
        val outcome = cms.verify(content, signature, alg, listOf(other.certificateDer, key.certificateDer))
        assertEquals(DetachedVerification.Verified(trustedIndex = 1), outcome)
    }

    @Test
    fun `the signature is detached - verifying other content fails`() {
        val key = signingKey("secp256r1")
        val signature = cms.sign(content, key)
        val outcome = cms.verify(content + 1, signature, alg, listOf(key.certificateDer))
        assertInstanceOf(DetachedVerification.SignatureInvalid::class.java, outcome)
    }

    @Test
    fun `a valid signature by an untrusted certificate is refused`() {
        val key = signingKey("secp256r1")
        val signature = cms.sign(content, key)
        val outcome = cms.verify(content, signature, alg, listOf(signingKey("secp256r1").certificateDer))
        assertEquals(DetachedVerification.UntrustedSigner, outcome)
    }

    @Test
    fun `bytes that are not CMS are refused`() {
        val key = signingKey("secp256r1")
        val outcome = cms.verify(content, byteArrayOf(1, 2, 3), alg, listOf(key.certificateDer))
        val invalid = assertInstanceOf(DetachedVerification.SignatureInvalid::class.java, outcome)
        assertTrue(invalid.detail.startsWith("not a well-formed CMS SignedData"), invalid.detail)
    }

    @Test
    fun `a signer key on another curve is refused even when trusted`() {
        val key = signingKey("secp384r1")
        val signature = cms.sign(content, key)
        val outcome = cms.verify(content, signature, alg, listOf(key.certificateDer))
        val invalid = assertInstanceOf(DetachedVerification.SignatureInvalid::class.java, outcome)
        assertTrue(invalid.detail.contains("curve"), invalid.detail)
    }

    private fun signingKey(curve: String): JcaSigningKey {
        val generator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        generator.initialize(ECGenParameterSpec(curve))
        val keyPair = generator.generateKeyPair()
        return JcaSigningKey(alg, keyPair.private, selfSigned(keyPair), BouncyCastleProvider.PROVIDER_NAME)
    }

    private fun selfSigned(keyPair: KeyPair): ByteArray {
        val now = Instant.now()
        val subject = X500Name("CN=crypto-test-${now.toEpochMilli()}")
        val signer =
            JcaContentSignerBuilder("SHA256withECDSA")
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(keyPair.private)
        return JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(System.nanoTime()),
            Date.from(now.minus(1, ChronoUnit.DAYS)),
            Date.from(now.plus(1, ChronoUnit.DAYS)),
            subject,
            keyPair.public,
        ).build(signer).encoded
    }

    private companion object {
        init {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }
}
