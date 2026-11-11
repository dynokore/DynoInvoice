package com.dynokore.gst.aggregation.adapters.periods

import com.dynokore.gst.aggregation.application.PeriodBoundarySource
import com.dynokore.gst.aggregation.application.PeriodSpans
import java.time.LocalDate

/**
 * The production [PeriodBoundarySource] (INV-REQ-435, INV-Q-14444 A, INV-Q-16535 A): the stored spans that overlap the
 * asked dates, earliest first. An entity with no stored set is [PeriodSpans.Unknown], so `CloseWindows` holds the
 * window; a stored set with a gap is returned as stored and the split fails closed.
 */
class StoredPeriodBoundaries(
    private val store: JooqPeriodSpanStore,
) : PeriodBoundarySource {
    override fun spans(
        entityRef: String,
        from: LocalDate,
        to: LocalDate,
    ): PeriodSpans = store.overlapping(entityRef, from, to)?.let { PeriodSpans.Known(it) } ?: PeriodSpans.Unknown
}
