package com.dynokore.platform.evidencestore

import com.dynokore.platform.crypto.HashAlgorithm
import com.dynokore.platform.crypto.Hashing

/**
 * A record's SHA-256 content hash, hex-encoded lower-case (FR-9.15 behaviour 2:
 * "per-record SHA-256, chained per entity and period"). CS-06: a value class,
 * never a bare `String`, so a hash and an arbitrary string cannot be confused
 * at a call site.
 */
@JvmInline
value class ContentHash(
    val hex: String,
) {
    init {
        require(HEX_64.matches(hex)) { "ContentHash must be 64 lower-case hex characters (SHA-256), was '$hex'" }
    }

    companion object {
        private val HEX_64 = Regex("^[0-9a-f]{64}$")

        /** SHA-256 of [bytes], hex-encoded, through the platform crypto module (CS-53) — no framework (ARC-16). */
        fun of(bytes: ByteArray): ContentHash = ContentHash(Hashing.hex(HashAlgorithm.SHA_256, bytes))
    }
}
