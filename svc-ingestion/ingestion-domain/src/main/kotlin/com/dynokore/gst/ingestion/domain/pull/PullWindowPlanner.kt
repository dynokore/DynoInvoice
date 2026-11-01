package com.dynokore.gst.ingestion.domain.pull

import java.time.Duration
import java.time.Instant

@JvmInline
value class PostingWatermark(
    val postingTimestamp: Instant,
)

data class LookBackBounds(
    val minDays: Long,
    val maxDays: Long,
) {
    init {
        require(minDays <= maxDays) { "minDays must not exceed maxDays" }
    }
}

enum class PullWindowRefusal {
    LOOK_BACK_BELOW_MINIMUM,
    LOOK_BACK_ABOVE_MAXIMUM,
    NO_WATERMARK,
    WATERMARK_AFTER_NOW,
}

sealed interface PullWindow {
    data class Planned(
        val from: Instant,
        val until: Instant,
    ) : PullWindow

    data class Refused(
        val reason: PullWindowRefusal,
    ) : PullWindow
}

object PullWindowPlanner {
    fun plan(
        watermark: PostingWatermark?,
        lookBackDays: Long,
        bounds: LookBackBounds,
        now: Instant,
    ): PullWindow =
        if (lookBackDays < bounds.minDays) {
            PullWindow.Refused(PullWindowRefusal.LOOK_BACK_BELOW_MINIMUM)
        } else if (lookBackDays > bounds.maxDays) {
            PullWindow.Refused(PullWindowRefusal.LOOK_BACK_ABOVE_MAXIMUM)
        } else if (watermark == null) {
            PullWindow.Refused(PullWindowRefusal.NO_WATERMARK)
        } else if (watermark.postingTimestamp.isAfter(now)) {
            PullWindow.Refused(PullWindowRefusal.WATERMARK_AFTER_NOW)
        } else {
            PullWindow.Planned(watermark.postingTimestamp.minus(Duration.ofDays(lookBackDays)), now)
        }
}
