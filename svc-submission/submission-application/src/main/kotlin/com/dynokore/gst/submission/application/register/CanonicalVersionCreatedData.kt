package com.dynokore.gst.submission.application.register

import com.dynokore.platform.outboxandqueue.ContractViolation
import java.time.LocalDate

/**
 * The members of a `CanonicalVersionCreated` event the register writer reads. [payload] holds the members that
 * `ListFactsPolicy.extract` reads (`canonicalDocument`, `routingFacts` and the canonical members it names).
 */
class CanonicalVersionCreatedData(
    val docUuid: String,
    val version: Int,
    val transactionDate: LocalDate,
    val prescribedPeriodRef: String,
    val scopeVerdict: String,
    val payload: Map<String, Any?>,
)

/** Reads the event data JSON of a `CanonicalVersionCreated` (the JSON library stays in the adapters, ARC-03). */
fun interface CanonicalVersionCreatedDataReader {
    /** @throws ContractViolation when a member is missing or malformed (dead letter at once). */
    fun read(dataJson: String): CanonicalVersionCreatedData
}
