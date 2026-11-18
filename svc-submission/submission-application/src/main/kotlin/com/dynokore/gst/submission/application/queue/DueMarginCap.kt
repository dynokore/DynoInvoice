package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.application.pack.MarginUnit
import com.dynokore.gst.submission.application.pack.PackMargin
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The filing due date plus one day at start of day in the business zone, minus the safety margin. Pure. */
object DueMarginCap {
    fun of(
        dueDate: LocalDate,
        margin: PackMargin,
        zone: ZoneId,
    ): Instant {
        val due = dueDate.plusDays(1).atStartOfDay(zone)
        return when (margin.unit) {
            MarginUnit.DAYS -> due.minusDays(margin.value.toLong()).toInstant()
            MarginUnit.HOURS -> due.toInstant().minus(Duration.ofHours(margin.value.toLong()))
        }
    }
}
