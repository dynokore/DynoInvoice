package com.dynokore.platform.evidencestore

import java.security.MessageDigest

/**
 * A record's SHA-256 content hash, hex-encoded lower-case (FR-9.15 behaviour 2:
 * "per-record SHA-256, chained per entity and period"). CS-06: a value class,
 * never a bare `String`, so a hash and an arbitrary string cannot be confused
 * at a call site.
 */
@JvmInline
value class ContentHash(val hex: String) {
    init {
        require(HEX_64.matches(hex)) { "ContentHash must be 64 lower-case hex characters (SHA-256), was '$hex'" }
    }

    companion object {
        private val HEX_64 = Regex("^[0-9a-f]{64}$")
        private val HEX_CHARS = "0123456789abcdef".toCharArray()
        private const val HEX_CHARS_PER_BYTE = 2
        private const val NIBBLE_BITS = 4
        private const val BYTE_MASK = 0xFF
        private const val LOW_NIBBLE_MASK = 0x0F

        /** SHA-256 of [bytes], hex-encoded. Pure JDK (`java.security.MessageDigest`) — no framework (ARC-16). */
        fun of(bytes: ByteArray): ContentHash {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hex = CharArray(digest.size * HEX_CHARS_PER_BYTE)
            digest.forEachIndexed { i, b ->
                val v = b.toInt() and BYTE_MASK
                hex[i * HEX_CHARS_PER_BYTE] = HEX_CHARS[v ushr NIBBLE_BITS]
                hex[i * HEX_CHARS_PER_BYTE + 1] = HEX_CHARS[v and LOW_NIBBLE_MASK]
            }
            return ContentHash(String(hex))
        }
    }
}
