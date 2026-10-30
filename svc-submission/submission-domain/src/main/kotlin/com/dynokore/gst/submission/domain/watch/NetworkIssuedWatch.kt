package com.dynokore.gst.submission.domain.watch

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** One version of a document, as read by the caller; [issuedAt] is when it entered NETWORK_ISSUED. */
data class WatchItem(
    val docUuid: UUID,
    val version: Int,
    val state: LifecycleState,
    val issuedAt: Instant,
    val raised: Boolean,
)

data class VersionRef(
    val docUuid: UUID,
    val version: Int,
)

/** [raise] lists versions for SUB-014; [thresholdMissing] lists watched versions when no threshold is set. */
data class WatchResult(
    val raise: List<VersionRef>,
    val thresholdMissing: List<VersionRef>,
)

/**
 * INV-REQ-329 (INV-Q-6423, option A): a NETWORK_ISSUED version not yet raised is listed for SUB-014 when `now` is
 * strictly after `issuedAt + threshold` (an acknowledgement exactly at the threshold is in time); a missing threshold
 * raises nothing. Pure: no state, no clock.
 */
object NetworkIssuedWatch {
    fun evaluate(
        items: List<WatchItem>,
        threshold: Duration?,
        now: Instant,
    ): WatchResult {
        require(threshold == null || (!threshold.isNegative && !threshold.isZero)) {
            "threshold must be greater than zero: $threshold"
        }
        val watched = items.filter { it.state == LifecycleState.NETWORK_ISSUED && !it.raised }
        if (threshold == null) {
            return WatchResult(emptyList(), watched.map(::ref))
        }
        return WatchResult(watched.filter { now.isAfter(it.issuedAt.plus(threshold)) }.map(::ref), emptyList())
    }

    private fun ref(item: WatchItem) = VersionRef(item.docUuid, item.version)
}
