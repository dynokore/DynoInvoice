package com.dynokore.gst.submission.domain.adjustment

import com.dynokore.gst.submission.domain.metrics.RowKind

/** Why a credit note's Doc UUID is refused (INV-REQ-544). */
enum class IdentityRefusal {
    MALFORMED_DOC_UUID,
    SAME_AS_PRECEDING,
    REGISTERED_AS_INVOICE,
}

sealed interface CreditNoteIdentityVerdict {
    data class Separate(
        val docUuid: String,
        val precedingDocUuid: String?,
    ) : CreditNoteIdentityVerdict

    data class Refused(
        val reason: IdentityRefusal,
    ) : CreditNoteIdentityVerdict
}

/**
 * Decides that a credit note is recorded under a Doc UUID of its own, never as a version of the document it
 * adjusts (INV-REQ-544, INV-AC-14146). Pure and total: no clock, store or global.
 */
object CreditNoteIdentity {
    private val wellFormed = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun check(
        docUuid: String,
        precedingDocUuid: String?,
        registeredKind: RowKind?,
    ): CreditNoteIdentityVerdict {
        val malformed =
            !wellFormed.matches(docUuid) || (precedingDocUuid != null && !wellFormed.matches(precedingDocUuid))
        val same = precedingDocUuid != null && docUuid.equals(precedingDocUuid, ignoreCase = true)
        val refusal =
            if (malformed) {
                IdentityRefusal.MALFORMED_DOC_UUID
            } else if (same) {
                IdentityRefusal.SAME_AS_PRECEDING
            } else if (registeredKind == RowKind.INVOICE) {
                IdentityRefusal.REGISTERED_AS_INVOICE
            } else {
                null
            }
        return if (refusal == null) {
            CreditNoteIdentityVerdict.Separate(docUuid, precedingDocUuid)
        } else {
            CreditNoteIdentityVerdict.Refused(refusal)
        }
    }
}
