package com.dynokore.gst.submission.application.queue

import com.dynokore.platform.outboxandqueue.ContractViolation

/** The members of a `PayloadReady` event the consumer reads; the payload bytes are already decoded from base64. */
@Suppress("LongParameterList")
class PayloadReadyData(
    val docUuid: String,
    val version: Int,
    val submissionType: String,
    val specificationVersion: String,
    val payloadBytes: ByteArray,
    val payloadSha256: String,
    /** The aggregate the document belongs to; null when the event carries no such member. */
    val aggregateId: String? = null,
    /** The class of this version relative to its predecessor; null when the event carries no such member. */
    val amendmentClass: String? = null,
)

/** Reads the event data JSON of a `PayloadReady` (the JSON library stays in the adapters, ARC-03). */
fun interface PayloadReadyDataReader {
    /** @throws ContractViolation when a member is missing or malformed (dead letter at once). */
    fun read(dataJson: String): PayloadReadyData
}
