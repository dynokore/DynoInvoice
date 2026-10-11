package com.dynokore.platform.crypto

/**
 * The named hash algorithms of the platform (CS-53). A caller names one; it never passes a JCA
 * algorithm string of its own.
 */
enum class HashAlgorithm(
    internal val jcaName: String,
    val digestLengthBytes: Int,
) {
    /** SHA-256 (FIPS 180-4): evidence content hashes (FR-9.15), pack checksums (STK-13). */
    SHA_256("SHA-256", SHA_256_DIGEST_BYTES),
}

private const val SHA_256_DIGEST_BYTES = 32
