package com.dynokore.gst.submission.application.replay

import com.dynokore.gst.submission.domain.replay.ReplayScope
import com.dynokore.gst.submission.domain.retry.RetryCode

/**
 * The failure classes a dead-lettered attempt can store (INV-REQ-575, delegated ruling of owner 2026-10-14): the
 * `value` of every [RetryCode], the only code enum the writer ([com.dynokore.gst.submission.application.queue
 * .AttemptOutcomeApplier]) raises with a dead letter. Matching is by string equality; look-alikes are refused.
 */
object ReplayFailureClasses {
    val STORABLE: Set<String> = RetryCode.entries.mapTo(LinkedHashSet()) { it.value }

    /**
     * Refuses a scope naming a class that no attempt can hold, before any read: such a scope would match nothing and
     * hide a typing mistake.
     *
     * @throws IllegalArgumentException when the class is not null and not in [STORABLE].
     */
    fun validate(scope: ReplayScope) {
        val failureClass = scope.failureClass ?: return
        require(failureClass in STORABLE) { "failure class is not one the writer stores" }
    }
}
