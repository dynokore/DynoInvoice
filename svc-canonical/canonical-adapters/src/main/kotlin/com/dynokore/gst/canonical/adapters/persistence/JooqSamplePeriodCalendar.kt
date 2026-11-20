package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.application.sample.SamplePeriodCalendar
import com.dynokore.gst.canonical.application.sample.SamplePeriods
import org.jooq.DSLContext
import java.util.UUID

/**
 * TPL-11. The period calendar of the dry-run sample over the event-fed period spans ([JooqPeriodSpans.spansOf], in
 * start order). Reads no other table. The DSLContext is the one of the open `canonical` transaction bound to a tenant.
 */
class JooqSamplePeriodCalendar(
    dsl: DSLContext,
) : SamplePeriodCalendar {
    private val spans = JooqPeriodSpans(dsl)

    override fun spans(
        tenantId: UUID,
        entityRef: String,
        firstPeriodRef: String,
        lastPeriodRef: String,
    ): SamplePeriods {
        val all = spans.spansOf(tenantId, entityRef)
        val first = all.indexOfFirst { it.periodRef == firstPeriodRef }
        val last = all.indexOfFirst { it.periodRef == lastPeriodRef }
        if (first < 0 || last < 0 || all[last].start.isBefore(all[first].start)) return SamplePeriods.Unknown
        return SamplePeriods.Known(all.subList(first, last + 1))
    }
}
