package com.dynokore.platform.outboxandqueue

import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/** UUIDv7 (RFC 9562) generator for event ids (S2-01): time-ordered, random tail from [SecureRandom] (CS-53). */
class UuidV7(
    private val random: SecureRandom = SecureRandom(),
) {
    /** A new UUIDv7 whose timestamp is [at]. */
    fun next(at: Instant): UUID {
        val bytes = ByteArray(RANDOM_BYTES).also { random.nextBytes(it) }
        val millis = at.toEpochMilli()
        var high = (millis shl TIME_SHIFT) or (VERSION_7 shl VERSION_SHIFT)
        high = high or ((bytes[0].toLong() and 0xFF) shl 4) or ((bytes[1].toLong() and 0xFF) shr 4)
        var low = 0L
        for (index in 2 until RANDOM_BYTES) {
            low = (low shl 8) or (bytes[index].toLong() and 0xFF)
        }
        low = (low and VARIANT_MASK) or VARIANT_BITS
        return UUID(high, low)
    }

    private companion object {
        const val RANDOM_BYTES = 10
        const val TIME_SHIFT = 16
        const val VERSION_SHIFT = 12
        const val VERSION_7 = 7L
        const val VARIANT_MASK = 0x3FFFFFFFFFFFFFFFL
        const val VARIANT_BITS = Long.MIN_VALUE
    }
}
