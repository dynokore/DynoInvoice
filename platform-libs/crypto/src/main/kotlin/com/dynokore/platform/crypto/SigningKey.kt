package com.dynokore.platform.crypto

/**
 * A signing key and the certificate that names it, wherever the private key is held: a cloud KMS,
 * an HSM, or a key file (INV-REQ-1127). The private key never leaves its holder: [sign] hands the
 * holder the bytes to sign and gets the signature back, so a non-exportable KMS or HSM key fits
 * the same port as a file key.
 */
interface SigningKey {
    val algorithm: SignatureAlgorithm

    /** The DER-encoded X.509 certificate of the key: carried in the CMS message and pinned by verifiers. */
    val certificateDer: ByteArray

    /**
     * The raw signature over [data] under [algorithm]; for [SignatureAlgorithm.ECDSA_P256_SHA256]
     * the DER `ECDSA-Sig-Value` over SHA-256 of [data] (what a JCA `SHA256withECDSA` signature or a
     * KMS `ECDSA_SHA_256` sign of the raw message returns).
     */
    fun sign(data: ByteArray): ByteArray
}
