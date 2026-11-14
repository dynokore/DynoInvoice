package com.dynokore.gst.submission.adapters.http

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * The calendar date of a filing (INV-REQ-914): the contract carries an instant, the stored fact a civil date derived
 * in the configured business zone (ISO 8601; `inv.filing.business-zone`, coordinator ruling round z, 2026-11-12).
 */
object FilingBinding {
    /** The civil date of [instant] in [zone]. */
    fun toBusinessDate(
        instant: OffsetDateTime,
        zone: ZoneId,
    ): LocalDate = instant.atZoneSameInstant(zone).toLocalDate()

    /** The start of [date] in [zone], carrying that zone's offset on that date. */
    fun startOfDay(
        date: LocalDate,
        zone: ZoneId,
    ): OffsetDateTime = date.atStartOfDay(zone).toOffsetDateTime()
}
