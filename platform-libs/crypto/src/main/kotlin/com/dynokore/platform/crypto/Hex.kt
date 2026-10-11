package com.dynokore.platform.crypto

/** Lower-case hex encoding, the one form every platform digest is written in. */
object Hex {
    private val HEX_CHARS = "0123456789abcdef".toCharArray()
    private const val HEX_CHARS_PER_BYTE = 2
    private const val NIBBLE_BITS = 4
    private const val BYTE_MASK = 0xFF
    private const val LOW_NIBBLE_MASK = 0x0F

    fun encode(bytes: ByteArray): String {
        val hex = CharArray(bytes.size * HEX_CHARS_PER_BYTE)
        bytes.forEachIndexed { i, b ->
            val v = b.toInt() and BYTE_MASK
            hex[i * HEX_CHARS_PER_BYTE] = HEX_CHARS[v ushr NIBBLE_BITS]
            hex[i * HEX_CHARS_PER_BYTE + 1] = HEX_CHARS[v and LOW_NIBBLE_MASK]
        }
        return String(hex)
    }
}
