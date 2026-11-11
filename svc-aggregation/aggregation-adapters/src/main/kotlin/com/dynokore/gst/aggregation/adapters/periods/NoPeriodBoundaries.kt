package com.dynokore.gst.aggregation.adapters.periods

import com.dynokore.gst.aggregation.application.PeriodBoundarySource
import com.dynokore.gst.aggregation.application.PeriodSpans
import java.time.LocalDate

/**
 * The production [PeriodBoundarySource] until INV-T-16565 (slice 3e) reads the real spans (INV-Q-14444 A,
 * INV-Q-16535 A): no span is known for any entity, so `CloseWindows` holds the window and never splits it on a guess.
 */
class NoPeriodBoundaries : PeriodBoundarySource {
    override fun spans(
        entityRef: String,
        from: LocalDate,
        to: LocalDate,
    ): PeriodSpans = PeriodSpans.Unknown
}
