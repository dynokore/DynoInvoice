package com.dynokore.platform.crypto

/**
 * Secret key material for one [MacAlgorithm]. The bytes are copied in and never exposed again;
 * [toString] never prints them.
 */
class MacKey(
    val algorithm: MacAlgorithm,
    keyBytes: ByteArray,
) {
    private val bytes: ByteArray = keyBytes.copyOf()

    init {
        require(bytes.size >= algorithm.minKeyLengthBytes) {
            "a ${algorithm.name} key must be at least ${algorithm.minKeyLengthBytes} bytes, was ${bytes.size}"
        }
    }

    internal fun material(): ByteArray = bytes.copyOf()

    override fun toString(): String = "MacKey(${algorithm.name}, ${bytes.size}B)"

    companion object {
        /** A fresh random key of [MacAlgorithm.minKeyLengthBytes] from [SecureTokens]. */
        fun generate(algorithm: MacAlgorithm): MacKey {
            val keyBytes = SecureTokens.randomBytes(algorithm.minKeyLengthBytes)
            return MacKey(algorithm, keyBytes)
        }
    }
}
