package com.dynokore.gst.evidence.domain.payload

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** The payload bytes held from PayloadReady, with the SHA-256 the producer stated for them. */
class HeldPayload(
    val docUuid: UUID,
    val version: Int,
    payloadBytes: ByteArray,
    val payloadSha256: String,
) {
    private val bytes: ByteArray = payloadBytes.copyOf()

    init {
        require(version >= 1) { "a version is at least 1" }
    }

    fun payloadBytes(): ByteArray = bytes.copyOf()

    /** True exactly when the lower-case hexadecimal SHA-256 of the held bytes equals [payloadSha256] as a string. */
    fun bytesMatchDigest(): Boolean = sha256Hex(bytes) == payloadSha256
}

/** A SubmissionDespatched event reduced to what the seal decision needs. */
data class Despatch(
    val docUuid: UUID,
    val version: Int,
    val attemptNumber: Int,
    val payloadSha256: String,
    val despatchedAt: Instant,
) {
    init {
        require(version >= 1) { "a version is at least 1" }
        require(attemptNumber >= 1) { "an attempt number is at least 1" }
    }
}

/** A despatched version bound to the exact bytes transmitted. */
class SealedPayload internal constructor(
    val docUuid: UUID,
    val version: Int,
    val attemptNumber: Int,
    val payloadSha256: String,
    val despatchedAt: Instant,
    payloadBytes: ByteArray,
) {
    private val bytes: ByteArray = payloadBytes.copyOf()

    fun payloadBytes(): ByteArray = bytes.copyOf()

    /** UTF-8 JSON, six keys in a fixed order, no whitespace; no value needs escaping. */
    fun contentJson(): ByteArray =
        (
            "{\"docUuid\":\"$docUuid\",\"version\":$version,\"attemptNumber\":$attemptNumber," +
                "\"payloadSha256\":\"$payloadSha256\",\"despatchedAt\":\"$despatchedAt\"," +
                "\"payloadBase64\":\"${Base64.getEncoder().encodeToString(bytes)}\"}"
        ).toByteArray(Charsets.UTF_8)
}

enum class SealRefusal {
    HELD_IDENTITY_MISMATCH,
    HELD_BYTES_DIGEST_MISMATCH,
    DESPATCHED_DIGEST_MISMATCH,
}

sealed interface SealDecision {
    /** No payload is held for the despatch yet. */
    data object AwaitPayload : SealDecision

    data class Refused(
        val reason: SealRefusal,
    ) : SealDecision

    data class Seal(
        val payload: SealedPayload,
    ) : SealDecision
}

object TransmittedPayloadSeal {
    fun decide(
        held: HeldPayload?,
        despatch: Despatch,
    ): SealDecision =
        when {
            held == null -> {
                SealDecision.AwaitPayload
            }

            held.docUuid != despatch.docUuid || held.version != despatch.version -> {
                SealDecision.Refused(SealRefusal.HELD_IDENTITY_MISMATCH)
            }

            !held.bytesMatchDigest() -> {
                SealDecision.Refused(SealRefusal.HELD_BYTES_DIGEST_MISMATCH)
            }

            despatch.payloadSha256 != held.payloadSha256 -> {
                SealDecision.Refused(SealRefusal.DESPATCHED_DIGEST_MISMATCH)
            }

            else -> {
                SealDecision.Seal(
                    SealedPayload(
                        despatch.docUuid,
                        despatch.version,
                        despatch.attemptNumber,
                        despatch.payloadSha256,
                        despatch.despatchedAt,
                        held.payloadBytes(),
                    ),
                )
            }
        }
}

private fun sha256Hex(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
