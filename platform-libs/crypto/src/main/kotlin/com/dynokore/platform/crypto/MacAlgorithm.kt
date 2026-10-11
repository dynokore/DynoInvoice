package com.dynokore.platform.crypto

/** The named MAC algorithms of the platform (CS-53). */
enum class MacAlgorithm(
    internal val jcaName: String,
    /** The shortest key accepted: the tag length (RFC 2104 §3 strongly discourages a shorter key). */
    val minKeyLengthBytes: Int,
) {
    /** HMAC with SHA-256 (RFC 2104, FIPS 198-1). */
    HMAC_SHA_256("HmacSHA256", HMAC_SHA_256_TAG_BYTES),
}

private const val HMAC_SHA_256_TAG_BYTES = 32
