package com.dynokore.gst.controlPlane.adapters.testing

import com.dynokore.gst.controlPlane.application.pack.PackSigningKeys
import com.dynokore.platform.crypto.JcaSigningKey
import com.dynokore.platform.crypto.SignatureAlgorithm
import com.dynokore.platform.crypto.SigningKey
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date

/**
 * A P-256 signing key with a self-signed certificate, for the bundle tests (STK-13). Generated per call, held in memory
 * and never written anywhere: the production key lives in an HSM behind PKCS#11 (INV-Q-7459).
 */
class SigningFixtures private constructor(
    val keyRef: String,
    val key: SigningKey,
) {
    /** The port the service reads: [keyRef] resolves to [key], any other reference is unknown. */
    val keys: PackSigningKeys = PackSigningKeys { ref -> key.takeIf { ref == keyRef } }

    /** The platform verification certificate (DER), the trust anchor a verifier is given. */
    val certificateDer: ByteArray get() = key.certificateDer

    companion object {
        fun create(
            keyRef: String = "KEY-REG-01",
            commonName: String = "pack-signing-test",
        ): SigningFixtures {
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            val pair = generator.generateKeyPair()
            val now = Instant.now()
            val subject = X500Name("CN=$commonName")
            val certificate =
                JcaX509v3CertificateBuilder(
                    subject,
                    BigInteger.valueOf(now.toEpochMilli()),
                    Date.from(now.minus(1, ChronoUnit.DAYS)),
                    Date.from(now.plus(3650, ChronoUnit.DAYS)),
                    subject,
                    pair.public,
                ).build(JcaContentSignerBuilder("SHA256withECDSA").build(pair.private))
            return SigningFixtures(
                keyRef,
                JcaSigningKey(SignatureAlgorithm.ECDSA_P256_SHA256, pair.private, certificate.encoded),
            )
        }
    }
}
