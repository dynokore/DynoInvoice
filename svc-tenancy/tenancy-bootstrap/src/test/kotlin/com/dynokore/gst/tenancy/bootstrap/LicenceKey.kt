package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.licence.SignedLicenceFiles
import com.dynokore.platform.crypto.BouncyCastleCms
import com.dynokore.platform.crypto.SignatureAlgorithm
import com.dynokore.platform.crypto.SigningKey
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date

/** An ECDSA P-256 key with a self-signed certificate: the vendor key of a licence fixture. */
internal class LicenceKey : SigningKey {
    private val pair: KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    override val algorithm = SignatureAlgorithm.ECDSA_P256_SHA256
    override val certificateDer: ByteArray

    init {
        val name = X500Name("CN=licence-test-${System.nanoTime()}")
        val builder =
            JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(System.nanoTime()),
                Date(System.currentTimeMillis() - 60_000),
                Date(System.currentTimeMillis() + 3_600_000),
                name,
                pair.public,
            )
        val holder = builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private))
        certificateDer = JcaX509CertificateConverter().getCertificate(holder).encoded
    }

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(data)
            sign()
        }

    val anchor: String get() = Base64.getEncoder().encodeToString(certificateDer)

    /** The signed file text of [members] (the licence object), signed over its RFC 8785 canonical bytes. */
    fun file(
        members: Map<String, String>,
        extra: String = "",
    ): ByteArray {
        val signature = BouncyCastleCms().sign(SignedLicenceFiles.canonical(members), this)
        val body = members.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
        val encoded = Base64.getEncoder().encodeToString(signature)
        return ("{\"licence\":{$body$extra},\"signature\":\"$encoded\"}").toByteArray()
    }

    companion object {
        fun licence(
            ref: String,
            installation: String,
            term: String,
        ) = linkedMapOf(
            "licenceRef" to ref,
            "installationRef" to installation,
            "term" to term,
            "issuedAt" to "2026-01-01",
        )
    }
}
