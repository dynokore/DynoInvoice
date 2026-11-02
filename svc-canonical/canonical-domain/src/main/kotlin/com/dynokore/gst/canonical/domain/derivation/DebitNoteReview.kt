package com.dynokore.gst.canonical.domain.derivation

import com.dynokore.gst.canonical.domain.netting.DocumentFunction

/** The catalogue hold code of a debit note held for review (VP-54, INV-REQ-1315). */
const val DEBIT_NOTE_REVIEW_CODE = "ING-024"

/** The VP-54 options of an entity, in the order of `catalogue.options`. */
enum class DebitNoteReviewOption {
    PACK_RULE,
    HOLD_FOR_REVIEW,
}

/** Pure decision of the documentFunction step for VP-54: no state, no clock. */
object DebitNoteReview {
    /**
     * Held only for a debit note that the entity elected to hold and that no person has [released]; every other
     * case is complete.
     */
    fun outcome(
        option: DebitNoteReviewOption,
        function: DocumentFunction,
        released: Boolean,
    ): StepOutcome =
        if (option == DebitNoteReviewOption.HOLD_FOR_REVIEW && function == DocumentFunction.DEBIT_NOTE && !released) {
            StepOutcome.HeldBy(DEBIT_NOTE_REVIEW_CODE)
        } else {
            StepOutcome.Complete
        }
}
