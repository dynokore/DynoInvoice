package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.platform.outboxandqueue.ContractViolation

/** Maps the wire submission type of a `PayloadReady` event to the domain kind (INV-REQ-975). */
object SubmissionTypeCode {
    private val table: Map<String, SubmissionKind> =
        mapOf(
            "1A" to SubmissionKind.TYPE_1A,
            "1B" to SubmissionKind.TYPE_1B,
            "2" to SubmissionKind.TYPE_2,
            "3" to SubmissionKind.TYPE_3,
        )

    /** Exact match only; any other value is a contract violation. */
    fun fromWire(wire: String): SubmissionKind =
        table[wire] ?: throw ContractViolation("PayloadReady submissionType '$wire' is not one of 1A, 1B, 2, 3")
}
