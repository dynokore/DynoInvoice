package com.dynokore.platform.crypto

import java.security.SecureRandom
import java.util.Base64

/**
 * Random tokens, passcodes and key material, always from [SecureRandom] (CS-53), never from
 * `kotlin.random.Random`, `java.util.Random` or `Math.random`.
 */
object SecureTokens {
    /** At least 128 bits for a token that must not be guessed. */
    const val MIN_TOKEN_BYTES = 16
    private const val DECIMAL_RADIX = 10
    private const val MAX_PASSCODE_DIGITS = 18

    private val random = SecureRandom()
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    /** [length] random bytes. */
    fun randomBytes(length: Int): ByteArray {
        require(length > 0) { "length must be positive, was $length" }
        return ByteArray(length).also(random::nextBytes)
    }

    /** A URL-safe, unpadded Base64 token of [byteLength] random bytes (at least [MIN_TOKEN_BYTES]). */
    fun urlSafeToken(byteLength: Int = 32): String {
        require(byteLength >= MIN_TOKEN_BYTES) { "a token needs at least $MIN_TOKEN_BYTES bytes, was $byteLength" }
        return urlEncoder.encodeToString(randomBytes(byteLength))
    }

    /** A numeric passcode of exactly [digits] decimal digits, uniformly distributed, leading zeros kept. */
    fun numericPasscode(digits: Int): String {
        require(digits in 1..MAX_PASSCODE_DIGITS) { "digits must be in 1..$MAX_PASSCODE_DIGITS, was $digits" }
        var bound = 1L
        repeat(digits) { bound *= DECIMAL_RADIX }
        return random.nextLong(bound).toString().padStart(digits, '0')
    }
}
