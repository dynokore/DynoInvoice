package com.dynokore.platform.rulepackkernel

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Security
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

/**
 * Test-only fixtures: a minimal "release train" of a signing key pair and
 * self-signed certificate (STK-13: ECDSA P-256 with SHA-256), and the plain
 * byte-level artefact construction [BouncyCastlePackVerifier] is written to
 * verify. Not `testFixtures` (`java-test-fixtures`): nothing outside this
 * module's own tests needs a CMS-signing helper (unlike tenancy-kernel's
 * Postgres probe, which a consuming service's own RLS tests reuse).
 */
object PackFixtures {
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun signingKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME)
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    private fun contentSigner(privateKey: PrivateKey): ContentSigner {
        val builder = JcaContentSignerBuilder("SHA256withECDSA").setProvider(BouncyCastleProvider.PROVIDER_NAME)
        return builder.build(privateKey)
    }

    /** A self-signed X.509 certificate over [keyPair] — stands in for a train's own signing certificate. */
    fun selfSignedCertificate(
        keyPair: KeyPair,
        commonName: String,
    ): X509CertificateHolder {
        val now = Instant.now()
        val subject = X500Name("CN=$commonName")
        val builder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now.toEpochMilli()),
            Date.from(now.minus(1, ChronoUnit.DAYS)),
            Date.from(now.plus(3650, ChronoUnit.DAYS)),
            subject,
            keyPair.public,
        )
        return builder.build(contentSigner(keyPair.private))
    }

    /** A detached CMS `SignedData` over [content], signed by [keyPair]/[certificate] (STK-13). */
    fun detachedCmsSignature(
        content: ByteArray,
        keyPair: KeyPair,
        certificate: X509CertificateHolder,
    ): ByteArray {
        val digestProvider =
            JcaDigestCalculatorProviderBuilder().setProvider(BouncyCastleProvider.PROVIDER_NAME).build()
        val signerInfoGenerator =
            JcaSignerInfoGeneratorBuilder(digestProvider).build(contentSigner(keyPair.private), certificate)

        val generator = CMSSignedDataGenerator()
        generator.addSignerInfoGenerator(signerInfoGenerator)
        generator.addCertificates(JcaCertStore(listOf(certificate)))

        // false: detached — contents travel separately, not encapsulated in the signature
        val signedData = generator.generate(CMSProcessableByteArray(content), false)
        return signedData.encoded
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    fun manifestJson(
        packId: String = "code-catalogue-sg",
        packType: String = "code-catalogue",
        version: String = "2026.10.1",
        effectiveFrom: String = "2026-10-01",
        effectiveTo: String? = null,
        minEngineVersion: String = "1.0.0",
    ): ByteArray {
        val effectiveToJson = effectiveTo?.let { "\"$it\"" } ?: "null"
        return """
            {"packId":"$packId","packType":"$packType","version":"$version",
             "effectiveFrom":"$effectiveFrom","effectiveTo":$effectiveToJson,
             "minEngineVersion":"$minEngineVersion"}
        """.trimIndent().toByteArray()
    }

    fun checksumsJson(contents: ByteArray): ByteArray = """{"contents":"${sha256Hex(contents)}"}""".toByteArray()

    /** A fully consistent, verifiable [PackArtefact], signed by [keyPair]/[certificate]. */
    fun artefact(
        contents: ByteArray = """{"rates":[{"code":"SR","rate":9}]}""".toByteArray(),
        manifest: ByteArray = manifestJson(),
        keyPair: KeyPair,
        certificate: X509CertificateHolder,
        checksums: ByteArray = checksumsJson(contents),
    ): PackArtefact {
        val signature = detachedCmsSignature(manifest + checksums, keyPair, certificate)
        return PackArtefact(manifest = manifest, checksums = checksums, signature = signature, contents = contents)
    }
}
