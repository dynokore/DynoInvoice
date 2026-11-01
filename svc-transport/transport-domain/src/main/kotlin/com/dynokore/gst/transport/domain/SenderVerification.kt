package com.dynokore.gst.transport.domain

import java.security.MessageDigest
import java.time.Clock
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/** Why a callback's sender was not verified (INV-Q-9668 check order); every one is answered 401 SRT-003. */
enum class SenderRefusal {
    NO_SECRET,
    NO_TIMESTAMP,
    TIMESTAMP_NOT_INTEGER,
    TIMESTAMP_STALE,
    NO_SIGNATURE,
    SIGNATURE_MISMATCH,
}

sealed interface SenderVerdict {
    data object Accepted : SenderVerdict

    data class Refused(
        val reason: SenderRefusal,
    ) : SenderVerdict
}

/**
 * Signed-payload sender verification (INV-Q-9668, API-27, INV-REQ-585 behaviour 2): `X-AP-Signature` is `sha256=` plus
 * 64 lowercase hex characters of HMAC-SHA256, keyed with the adapter secret, over the `X-AP-Timestamp` value, one `.`
 * and the raw body; the callback is fresh when the clock differs from the timestamp by at most [MAX_SKEW_SECONDS]
 * either way; the signature is compared in constant time. Pure: the clock is injected.
 */
class SenderVerifier(
    private val clock: Clock,
) {
    fun verify(
        body: ByteArray,
        timestamp: String?,
        signature: String?,
        secret: ByteArray,
    ): SenderVerdict {
        val signedAt = timestamp?.let(::parseEpochSeconds)
        val refusal =
            when {
                secret.isEmpty() -> SenderRefusal.NO_SECRET
                timestamp == null -> SenderRefusal.NO_TIMESTAMP
                signedAt == null -> SenderRefusal.TIMESTAMP_NOT_INTEGER
                abs(clock.instant().epochSecond - signedAt) > MAX_SKEW_SECONDS -> SenderRefusal.TIMESTAMP_STALE
                signature == null -> SenderRefusal.NO_SIGNATURE
                !signatureMatches(secret, timestamp, body, signature) -> SenderRefusal.SIGNATURE_MISMATCH
                else -> null
            }
        return if (refusal == null) SenderVerdict.Accepted else SenderVerdict.Refused(refusal)
    }

    private fun signatureMatches(
        secret: ByteArray,
        timestamp: String,
        body: ByteArray,
        signature: String,
    ): Boolean {
        val signed = timestamp.toByteArray(Charsets.US_ASCII) + DOT + body
        val expected = PREFIX + hex(hmac(secret, signed))
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), signature.toByteArray(Charsets.UTF_8))
    }

    /** Decimal Unix epoch seconds: ASCII digits only, at most 18 of them (so it fits a Long). */
    private fun parseEpochSeconds(value: String): Long? =
        if (value.length in 1..MAX_DIGITS && value.all { it in '0'..'9' }) value.toLong() else null

    private fun hmac(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray =
        Mac.getInstance(ALGORITHM).run {
            init(SecretKeySpec(key, ALGORITHM))
            doFinal(data)
        }

    private fun hex(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)

    companion object {
        const val MAX_SKEW_SECONDS = 300L
        private const val ALGORITHM = "HmacSHA256"
        private const val PREFIX = "sha256="
        private const val MAX_DIGITS = 18
        private val DOT = byteArrayOf('.'.code.toByte())
    }
}
