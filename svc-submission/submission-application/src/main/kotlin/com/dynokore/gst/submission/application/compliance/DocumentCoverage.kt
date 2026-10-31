package com.dynokore.gst.submission.application.compliance

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.ordering.LifecycleState.ACKNOWLEDGED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.AWAITING_PRECEDING
import com.dynokore.gst.submission.domain.ordering.LifecycleState.BLOCKED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.CANCELLED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.DEAD_LETTERED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.EXTERNALLY_SUBMITTED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.HELD
import com.dynokore.gst.submission.domain.ordering.LifecycleState.INGESTED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.NETWORK_ISSUED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.NOT_SUBMITTABLE
import com.dynokore.gst.submission.domain.ordering.LifecycleState.QUEUED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.READY
import com.dynokore.gst.submission.domain.ordering.LifecycleState.REJECTED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.RETRY
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SENT
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SENT_UNCONFIRMED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SUPERSEDED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.WITHDRAWN

/**
 * Whether a document counts as submitted for the coverage metric (INV-REQ-1069). It is the readiness verdict's
 * definition (INV-REQ-71, `counts_as_unsubmitted` of lifecycle-states.yaml), so the two never disagree.
 */
object DocumentCoverage {
    /** [versionStates] are the states of the version rows of one document in version order. */
    fun hasSubmission(versionStates: List<LifecycleState>): Boolean {
        if (versionStates.isEmpty()) return false
        val last = versionStates.last()
        val earlierAcknowledged = versionStates.dropLast(1).any { it == ACKNOWLEDGED }
        val unsubmitted =
            when (last) {
                INGESTED, HELD, BLOCKED, READY, AWAITING_PRECEDING, QUEUED, SENT, SENT_UNCONFIRMED, RETRY,
                DEAD_LETTERED, NETWORK_ISSUED,
                -> true

                REJECTED -> !earlierAcknowledged

                ACKNOWLEDGED, SUPERSEDED, EXTERNALLY_SUBMITTED, CANCELLED, WITHDRAWN, NOT_SUBMITTABLE -> false
            }
        return !unsubmitted
    }
}
