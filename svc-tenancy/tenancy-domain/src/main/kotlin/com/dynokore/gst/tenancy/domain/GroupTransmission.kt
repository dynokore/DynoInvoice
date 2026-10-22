package com.dynokore.gst.tenancy.domain

/** How a GST group transmits invoice data (VP-28, INV-REQ-1289): per member entity by default. */
enum class GroupTransmissionMode {
    PER_MEMBER,
    CONSOLIDATED,
    ;

    companion object {
        /** VP-28 is `planned` in the catalogue: every group has the default until it is available (INV-Q-8729 A). */
        val DEFAULT: GroupTransmissionMode = PER_MEMBER
    }
}

/** Where a held answer comes from and whether it is final. */
enum class AnswerSource { PROVISIONAL, CONFIRMED }

/** An answer held as data with its source marked (INV-Q-2839 is provisional until the external answer arrives). */
data class HeldAnswer(
    val question: String,
    val text: String,
    val source: AnswerSource,
)

object GroupTransmissionRules {
    /**
     * INV-Q-2839, provisional: in consolidated mode the representative member's Peppol ID is the Sender ID for every
     * member's document, each member's own otherwise, and a change of mode takes effect from the next prescribed
     * accounting period (INV-REQ-1289, INV-REQ-715).
     */
    val SENDER_ID_OF_REPRESENTATIVE_MEMBER =
        HeldAnswer(
            "INV-Q-2839",
            "The representative member's Peppol ID is the Sender ID for every member's document in consolidated " +
                "mode, and a mode change takes effect from the next prescribed accounting period",
            AnswerSource.PROVISIONAL,
        )
}
