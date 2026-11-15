package com.dynokore.gst.submission.domain.correction

import com.dynokore.gst.submission.domain.ordering.LifecycleState

/** How a version state counts for the standing of an original (INV-REQ-559 behavior 1, INV-Q-6623 option A). */
enum class StateClass { AT_IRAS, SENT_OUT, QUEUED_CLASS, NONE }

/** The standing of the original of an inbound document. */
enum class Standing { SUBMITTED, IN_FLIGHT, NOT_SUBMITTED }

object OriginalStanding {
    /** Exhaustive over [LifecycleState]: a new member fails to compile here. */
    fun classOf(state: LifecycleState): StateClass =
        when (state) {
            LifecycleState.ACKNOWLEDGED,
            LifecycleState.EXTERNALLY_SUBMITTED,
            LifecycleState.SUPERSEDED,
            -> StateClass.AT_IRAS

            LifecycleState.SENT,
            LifecycleState.SENT_UNCONFIRMED,
            LifecycleState.RETRY,
            LifecycleState.NETWORK_ISSUED,
            -> StateClass.SENT_OUT

            LifecycleState.QUEUED -> StateClass.QUEUED_CLASS

            LifecycleState.INGESTED,
            LifecycleState.HELD,
            LifecycleState.BLOCKED,
            LifecycleState.NOT_SUBMITTABLE,
            LifecycleState.READY,
            LifecycleState.AWAITING_PRECEDING,
            LifecycleState.REJECTED,
            LifecycleState.DEAD_LETTERED,
            LifecycleState.CANCELLED,
            LifecycleState.WITHDRAWN,
            -> StateClass.NONE
        }

    fun of(versions: List<LifecycleState>): Standing {
        val classes = versions.map(::classOf)
        return when {
            classes.any { it == StateClass.AT_IRAS } -> Standing.SUBMITTED
            classes.any { it == StateClass.SENT_OUT || it == StateClass.QUEUED_CLASS } -> Standing.IN_FLIGHT
            else -> Standing.NOT_SUBMITTED
        }
    }
}
