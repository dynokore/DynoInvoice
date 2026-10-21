package com.dynokore.gst.gatewayauthz.keys

/**
 * One key of getVerificationKeys (platform-contracts access/openapi.yaml VerificationKeysResponse): key id, JWS
 * algorithm and the public key bytes, held as received.
 */
class VerificationKey(
    val kid: String,
    val alg: String,
    publicKey: ByteArray,
) {
    private val bytes = publicKey.copyOf()

    val publicKey: ByteArray get() = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is VerificationKey &&
        other.kid == kid &&
        other.alg == alg &&
        other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * (31 * kid.hashCode() + alg.hashCode()) + bytes.contentHashCode()
}
