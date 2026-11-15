package com.dynokore.gst.submission.domain.transitions

import java.time.Duration
import java.time.Instant

/** The acknowledgement deadline of an accepted version: pure, no clock and no configuration read (INV-AC-22760). */
object AcknowledgementDeadline {
    /** `acceptedAt + threshold`, or the earlier of that and [cap] when [cap] is set; the threshold must be positive. */
    fun of(
        acceptedAt: Instant,
        threshold: Duration,
        cap: Instant?,
    ): Instant {
        require(!threshold.isZero && !threshold.isNegative) { "threshold must be positive, was $threshold" }
        val byThreshold = acceptedAt.plus(threshold)
        return if (cap == null) byThreshold else minOf(byThreshold, cap)
    }
}
