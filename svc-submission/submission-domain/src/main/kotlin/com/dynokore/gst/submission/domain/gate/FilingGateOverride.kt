package com.dynokore.gst.submission.domain.gate

/**
 * The status of a filing-gate override (INV-Q-10691 R1). Only [PENDING] and [RELEASED] are stored; [SUPERSEDED] is
 * derived at read time from a pending override whose check is no longer the latest and is never written.
 */
enum class OverrideStatus { PENDING, RELEASED, SUPERSEDED }

/** The answer of the release decision (`decideOverrideRelease`, INV-Q-10695), read fail closed. */
sealed interface ReleaseOutcome {
    /** The override is released. */
    data object Released : ReleaseOutcome

    /** The override waits for a second approver. */
    data object AwaitsSecond : ReleaseOutcome

    /** The release is refused, or the answer could not be read. */
    data class Denied(
        val reasonCode: String,
    ) : ReleaseOutcome

    companion object {
        /** The reason code of every denial that carries none of its own (contract `reasonCode`, INV-Q-17115 R4). */
        const val NOT_OVERRIDABLE = "NOT_OVERRIDABLE"

        private const val ALLOW = "ALLOW"
        private const val DENY = "DENY"
        private const val RELEASED = "RELEASED"
        private const val AWAITS_SECOND_APPROVER = "AWAITS_SECOND_APPROVER"

        /**
         * Builds the outcome by exact string equality. An unknown or missing decision or outcome, or ALLOW without
         * an outcome, is [Denied]: fail closed.
         */
        fun from(
            decision: String?,
            outcome: String?,
            reasonCode: String?,
        ): ReleaseOutcome =
            when (decision) {
                ALLOW -> {
                    when (outcome) {
                        RELEASED -> Released
                        AWAITS_SECOND_APPROVER -> AwaitsSecond
                        else -> Denied(NOT_OVERRIDABLE)
                    }
                }

                DENY -> {
                    Denied(reasonCode?.takeIf { it.isNotBlank() } ?: NOT_OVERRIDABLE)
                }

                else -> {
                    Denied(NOT_OVERRIDABLE)
                }
            }
    }
}
