package com.dynokore.gst.submission.domain.ordering

/**
 * The transmitted classification (INV-T-6427): true for exactly SENT, SENT_UNCONFIRMED, RETRY, DEAD_LETTERED,
 * ACKNOWLEDGED, SUPERSEDED, NETWORK_ISSUED and EXTERNALLY_SUBMITTED. REJECTED is not transmitted here.
 * Exhaustive over [LifecycleState]: a new member fails to compile.
 */
fun isTransmitted(state: LifecycleState): Boolean =
    when (state) {
        LifecycleState.SENT,
        LifecycleState.SENT_UNCONFIRMED,
        LifecycleState.RETRY,
        LifecycleState.DEAD_LETTERED,
        LifecycleState.ACKNOWLEDGED,
        LifecycleState.SUPERSEDED,
        LifecycleState.NETWORK_ISSUED,
        LifecycleState.EXTERNALLY_SUBMITTED,
        -> true

        LifecycleState.READY,
        LifecycleState.AWAITING_PRECEDING,
        LifecycleState.QUEUED,
        LifecycleState.REJECTED,
        LifecycleState.CANCELLED,
        LifecycleState.WITHDRAWN,
        LifecycleState.INGESTED,
        LifecycleState.HELD,
        LifecycleState.BLOCKED,
        LifecycleState.NOT_SUBMITTABLE,
        -> false
    }
