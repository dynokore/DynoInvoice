package com.dynokore.gst.submission.application.correction

import com.dynokore.gst.submission.domain.correction.CorrectionReason
import com.dynokore.gst.submission.domain.ordering.LifecycleState

/** The original a credit note names and its current version (the base of a net derivation request). */
data class OriginalRef(
    val docUuid: String,
    val currentVersion: Int,
)

/** Reads the register for the original of a credit note (INV-REQ-559 behavior 1). */
interface OriginalVersionSource {
    /** The current state of each version of the `precedingDocUuid` target; empty when no original is resolved. */
    fun versionsOf(creditNoteDocUuid: String): List<LifecycleState>

    /** The `precedingDocUuid` target with its current version; null when the credit note names no resolved original. */
    fun originalOf(creditNoteDocUuid: String): OriginalRef?
}

/** Whether a revised invoice lists the credit note in `nettedCreditNoteDocUuids`. */
fun interface NettingDeclarations {
    fun declared(creditNoteDocUuid: String): Boolean
}

/** The transitions of a credit note the correction path applies. Every call runs in the caller's transaction. */
interface CreditNoteTransitions {
    fun submitSeparately(creditNoteDocUuid: String)

    fun submitAsPosted(creditNoteDocUuid: String)

    fun wait(creditNoteDocUuid: String)

    fun notSubmittable(
        creditNoteDocUuid: String,
        reason: CorrectionReason,
    )

    /** The credit notes of [originalDocUuid] now NOT_SUBMITTABLE with reason NETTED_BY_SYSTEM. */
    fun nettedBySystem(originalDocUuid: String): List<String>

    /** Lifts a credit note out of NOT_SUBMITTABLE (reason NETTED_BY_SYSTEM) so that it can be decided again. */
    fun lift(creditNoteDocUuid: String)
}

/** Stages the `NetDerivationRequested` event in the caller's transaction (transactional outbox). */
fun interface NetDerivationRequestPort {
    fun request(
        originalDocUuid: String,
        expectedBaseVersion: Int,
        creditNoteDocUuids: List<String>,
    )
}

/** The per-credit-note flag `netRefused`: a net derivation was refused, so the credit note is never netted again. */
interface NetRefusedMarks {
    fun store(creditNoteDocUuid: String)

    fun isRefused(creditNoteDocUuid: String): Boolean
}
