package com.dynokore.gst.transport.domain

/** One reason a payload was refused, in the internal vocabulary: never an AP error code (INV-REQ-539 behaviour 2). */
data class FailureReason(
    val code: String,
    val field: String?,
    val message: String,
) {
    init {
        require(CODE.matches(code)) { "a reason code is PREFIX-NNN, got '$code'" }
        require(message.isNotBlank()) { "a reason has a message" }
    }

    companion object {
        private val CODE = Regex("[A-Z]{2,10}-[0-9]{3}")

        /** The structural refusal of a package (SUB-003, the code the transport contract lists). */
        const val PACKAGE_REFUSED = "SUB-003"
    }
}

/**
 * The only three answers of the transport interface (INV-REQ-795 behaviour 2): accepted with acknowledgement,
 * rejected with reasons, or transient failure. Nothing the AP reports travels beyond this type.
 */
sealed interface TransportOutcome {
    val kind: OutcomeKind

    data class Accepted(
        val acknowledgementId: String,
    ) : TransportOutcome {
        init {
            require(acknowledgementId.isNotBlank()) { "an acceptance carries its acknowledgement" }
        }

        override val kind: OutcomeKind get() = OutcomeKind.ACCEPTED
    }

    data class Rejected(
        val reasons: List<FailureReason>,
    ) : TransportOutcome {
        init {
            require(reasons.isNotEmpty()) { "a rejection carries at least one reason" }
        }

        override val kind: OutcomeKind get() = OutcomeKind.REJECTED
    }

    data object TransientFailure : TransportOutcome {
        override val kind: OutcomeKind get() = OutcomeKind.TRANSIENT_FAILURE
    }
}

enum class OutcomeKind {
    ACCEPTED,
    REJECTED,
    TRANSIENT_FAILURE,
    ;

    /** An attempt that did not end accepted counts as failed in the failure rate (INV-REQ-565). */
    val isFailure: Boolean get() = this != ACCEPTED
}
