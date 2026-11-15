package com.dynokore.gst.payload.application.payload

import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/**
 * The facts of one `PayloadReady` event. [payloadSha256] is the lower-case hex digest the caller states for
 * [payloadBytes]; [aggregateId] null means absent.
 */
@Suppress("LongParameterList") // the eight members of the PayloadReady schema plus the entity reference
class PayloadReadyFacts(
    val entityRef: String,
    val docUuid: UUID,
    val version: Int,
    val submissionType: String,
    val specificationVersion: String,
    val payloadBytes: ByteArray,
    val payloadSha256: String,
    val aggregateId: String?,
)

/** Stages a `PayloadReady` event for release; the adapter writes it to the outbox in the caller's transaction. */
fun interface PayloadReadyPublisher {
    fun stage(facts: PayloadReadyFacts)
}

/**
 * Stages `PayloadReady` once the stated digest matches the bytes. It runs in the caller's transaction and opens none.
 * It neither builds nor validates a payload and never decides the size (PAY-010 is decided by `PayloadSizeCheck`
 * of payload-domain before the call).
 */
class PublishPayloadReady(
    private val publisher: PayloadReadyPublisher,
) {
    /** Throws [IllegalArgumentException] and stages nothing when a fact is unacceptable. */
    fun publish(facts: PayloadReadyFacts) {
        require(facts.payloadBytes.isNotEmpty()) { "payloadBytes must not be empty" }
        require(facts.submissionType.isNotBlank()) { "submissionType must not be blank" }
        require(facts.specificationVersion.isNotBlank()) { "specificationVersion must not be blank" }
        require(facts.aggregateId == null || facts.aggregateId.isNotBlank()) { "aggregateId must not be blank" }
        // Exact comparison: the published schema pattern of payloadSha256 is lower-case hex.
        require(sha256Hex(facts.payloadBytes) == facts.payloadSha256) { "payloadSha256 does not match payloadBytes" }
        publisher.stage(facts)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
