package com.dynokore.gst.submission.application.register

import com.dynokore.platform.outboxandqueue.ContractViolation
import java.time.LocalDate

/**
 * [notSubmittableReason], [precedingDocUuid] and [netDerivation] are null exactly when the event omits the member.
 *
 * The members of a `CanonicalVersionCreated` event the register writer reads. [payload] holds the members that
 * `ListFactsPolicy.extract` reads (`canonicalDocument`, `routingFacts` and the canonical members it names).
 */
@Suppress("LongParameterList") // the optional members follow payload (INV-T-24493), defaulted
class CanonicalVersionCreatedData(
    val docUuid: String,
    val version: Int,
    val transactionDate: LocalDate,
    val prescribedPeriodRef: String,
    val scopeVerdict: String,
    val payload: Map<String, Any?>,
    val notSubmittableReason: String? = null,
    val precedingDocUuid: String? = null,
    val netDerivation: NetDerivationData? = null,
)

/** One credit note a derived version nets: its docUuid and version. */
data class NetDerivedCreditNote(
    val docUuid: String,
    val version: Int,
)

/** The `netDerivation` member of a derived version: the base version and the credit notes it nets. */
data class NetDerivationData(
    val baseVersion: Int,
    val creditNotes: List<NetDerivedCreditNote>,
)

/** Reads the event data JSON of a `CanonicalVersionCreated` (the JSON library stays in the adapters, ARC-03). */
fun interface CanonicalVersionCreatedDataReader {
    /** @throws ContractViolation when a member is missing or malformed (dead letter at once). */
    fun read(dataJson: String): CanonicalVersionCreatedData
}
