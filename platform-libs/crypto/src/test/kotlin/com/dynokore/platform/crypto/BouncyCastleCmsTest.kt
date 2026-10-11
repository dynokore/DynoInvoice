package com.dynokore.platform.crypto

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.SignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.DigestCalculatorProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
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

    @Test
    fun `a signer using another signature algorithm is refused even when trusted`() {
        val keyPair = keyPair("secp256r1")
        val key = signingKeyOf(keyPair)
        val signature = foreignCms(keyPair, key, contentSignature = "SHA384withECDSA", digestOverride = null)
        val outcome = cms.verify(content, signature, alg, listOf(key.certificateDer))
        val invalid = assertInstanceOf(DetachedVerification.SignatureInvalid::class.java, outcome)
        assertTrue(invalid.detail.contains("signature algorithm"), invalid.detail)
    }

    @Test
    fun `a signer using another digest is refused even when trusted`() {
        val keyPair = keyPair("secp256r1")
        val key = signingKeyOf(keyPair)
        val signature =
            foreignCms(
                keyPair,
                key,
                contentSignature = "SHA256withECDSA",
                digestOverride = NISTObjectIdentifiers.id_sha384,
            )
        val outcome = cms.verify(content, signature, alg, listOf(key.certificateDer))
        val invalid = assertInstanceOf(DetachedVerification.SignatureInvalid::class.java, outcome)
        assertTrue(invalid.detail.contains("digest algorithm"), invalid.detail)
    }

    @Test
    fun `an injected provider instance is used for signing and verification`() {
        val injected = BouncyCastleCms(BouncyCastleProvider())
        val key = signingKey("secp256r1")
        val signature = injected.sign(content, key)
        val outcome = injected.verify(content, signature, alg, listOf(key.certificateDer))
        assertEquals(DetachedVerification.Verified(0), outcome)
    }

    /**
     * A detached CMS signature made directly with Bouncy Castle, not through [BouncyCastleCms]:
     * [contentSignature] signs the signed attributes and [digestOverride], when set, replaces the
     * message digest algorithm the SignerInfo declares.
     */
    private fun foreignCms(
        keyPair: KeyPair,
        key: JcaSigningKey,
        contentSignature: String,
        digestOverride: ASN1ObjectIdentifier?,
    ): ByteArray {
        val base = JcaDigestCalculatorProviderBuilder().setProvider(BouncyCastleProvider.PROVIDER_NAME).build()
        val digests =
            if (digestOverride == null) {
                base
            } else {
                DigestCalculatorProvider { _ -> base.get(AlgorithmIdentifier(digestOverride)) }
            }
        val signer =
            JcaContentSignerBuilder(contentSignature)
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build(keyPair.private)
        val certificate = X509CertificateHolder(key.certificateDer)
        val generator = CMSSignedDataGenerator()
        generator.addSignerInfoGenerator(SignerInfoGeneratorBuilder(digests).build(signer, certificate))
        generator.addCertificates(JcaCertStore(listOf(certificate)))
        return generator.generate(CMSProcessableByteArray(content), false).encoded
    }

    private fun keyPair(curve: String): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        generator.initialize(ECGenParameterSpec(curve))
        return generator.generateKeyPair()
    }

    private fun signingKey(curve: String): JcaSigningKey {
        val keyPair = keyPair(curve)
        return signingKeyOf(keyPair)
    }

    private fun signingKeyOf(keyPair: KeyPair): JcaSigningKey {
        val certificateDer = selfSigned(keyPair)
        return JcaSigningKey(alg, keyPair.private, certificateDer, BouncyCastleProvider.PROVIDER_NAME)
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
