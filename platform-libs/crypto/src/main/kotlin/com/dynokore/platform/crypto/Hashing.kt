package com.dynokore.platform.crypto

import java.security.MessageDigest

/** Hashing with a named [HashAlgorithm] (CS-53). Pure JDK, framework-free (ARC-16). */
object Hashing {
    /** The digest of [bytes] under [algorithm]. */
    fun digest(
        algorithm: HashAlgorithm,
        bytes: ByteArray,
    ): ByteArray = MessageDigest.getInstance(algorithm.jcaName).digest(bytes)

    /** The digest of [bytes] under [algorithm], hex-encoded lower-case. */
    fun hex(
        algorithm: HashAlgorithm,
        bytes: ByteArray,
    ): String = Hex.encode(digest(algorithm, bytes))
}
