package com.dynokore.platform.crypto

import java.security.PrivateKey
import java.security.Signature

/**
 * A [SigningKey] over a JCA [PrivateKey]: a key file loaded by the service, or an HSM key exposed
 * through a PKCS#11 provider (INV-REQ-1127, private installation). [provider] names the JCA
 * provider that holds the key, `null` for the default lookup. A cloud KMS key implements
 * [SigningKey] directly instead.
 */
class JcaSigningKey(
    override val algorithm: SignatureAlgorithm,
    private val privateKey: PrivateKey,
    certificateDer: ByteArray,
    private val provider: String? = null,
) : SigningKey {
    private val certificate: ByteArray = certificateDer.copyOf()

    init {
        require(certificate.isNotEmpty()) { "certificateDer must not be empty" }
    }

    override val certificateDer: ByteArray get() = certificate.copyOf()

    override fun sign(data: ByteArray): ByteArray {
        val jcaName = JCA_SIGNATURE_NAMES.getValue(algorithm)
        val signer = if (provider == null) Signature.getInstance(jcaName) else Signature.getInstance(jcaName, provider)
        signer.initSign(privateKey)
        signer.update(data)
        return signer.sign()
    }

    override fun toString(): String = "JcaSigningKey(${algorithm.name})"

    private companion object {
        val JCA_SIGNATURE_NAMES = mapOf(SignatureAlgorithm.ECDSA_P256_SHA256 to "SHA256withECDSA")
    }
}
