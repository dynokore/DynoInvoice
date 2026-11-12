package com.dynokore.gst.submission.application.pack

import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

sealed interface PackMarginAnswer {
    data class Known(
        val margin: PackMargin,
    ) : PackMarginAnswer

    data object Unknown : PackMarginAnswer
}

/** The default safety margin of a date: the margin of the calendar pack version in force that day. */
fun interface PackDefaultMargin {
    fun on(
        tenant: TenantId,
        date: LocalDate,
    ): PackMarginAnswer
}

/**
 * Latest effective date on or before the date wins. Unknown (fail closed) when nothing is in force, when two
 * different versions share that date, or when the selected version has no margin. No older version is used instead.
 */
class StoredPackDefaultMargin(
    private val reader: CalendarActivationReader,
) : PackDefaultMargin {
    override fun on(
        tenant: TenantId,
        date: LocalDate,
    ): PackMarginAnswer {
        val inForce = reader.calendarActivations(tenant, date).filter { !it.effectiveFrom.isAfter(date) }
        val latest = inForce.maxOfOrNull { it.effectiveFrom }
        val selected = inForce.filter { it.effectiveFrom == latest }.distinctBy { it.packIdentifier to it.version }
        val margin = selected.singleOrNull()?.margin
        return if (margin == null) PackMarginAnswer.Unknown else PackMarginAnswer.Known(margin)
    }
}
