package com.dynokore.gst.rules.bootstrap

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Security
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

/** Test-only signing of pack bundles (STK-13: ECDSA P-256 with SHA-256, detached CMS) with a throw-away key. */
object TestPackSigning {
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) ==
            null
        ) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    class Key(
        private val pair: KeyPair,
        private val certificate: X509CertificateHolder,
    ) {
        val certificateDer: ByteArray get() = certificate.encoded

        /** A detached CMS signature over [content]. */
        fun sign(content: ByteArray): ByteArray {
            val digests = JcaDigestCalculatorProviderBuilder().setProvider(BouncyCastleProvider.PROVIDER_NAME).build()
            val signer = contentSigner(pair)
            val generator = CMSSignedDataGenerator()
            generator.addSignerInfoGenerator(JcaSignerInfoGeneratorBuilder(digests).build(signer, certificate))
            generator.addCertificates(JcaCertStore(listOf(certificate)))
            return generator.generate(CMSProcessableByteArray(content), false).encoded
        }
    }

    private fun contentSigner(pair: KeyPair) =
        JcaContentSignerBuilder("SHA256withECDSA").setProvider(BouncyCastleProvider.PROVIDER_NAME).build(pair.private)

    fun newKey(): Key {
        val generator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val now = Instant.now()
        val subject = X500Name("CN=test-train")
        val certificate =
            JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minus(1, ChronoUnit.DAYS)),
                Date.from(now.plus(3650, ChronoUnit.DAYS)),
                subject,
                pair.public,
            ).build(contentSigner(pair))
        return Key(pair, certificate)
    }
}
