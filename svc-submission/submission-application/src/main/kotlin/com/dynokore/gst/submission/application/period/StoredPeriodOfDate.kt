package com.dynokore.gst.submission.application.period

import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/** The answer of [PeriodOfDate]. */
sealed interface PeriodAnswer {
    data class Known(
        val periodRef: String,
    ) : PeriodAnswer

    data object Unknown : PeriodAnswer
}

/** The period of an entity that contains a date. */
fun interface PeriodOfDate {
    fun periodOf(
        tenant: TenantId,
        entityRef: String,
        date: LocalDate,
    ): PeriodAnswer
}

/** Known only when exactly one stored period contains the date; unknown for none or more than one. */
class StoredPeriodOfDate(
    private val reader: PeriodContainingReader,
) : PeriodOfDate {
    override fun periodOf(
        tenant: TenantId,
        entityRef: String,
        date: LocalDate,
    ): PeriodAnswer {
        val found = reader.containing(tenant, entityRef, date)
        return if (found.size == 1) PeriodAnswer.Known(found.single()) else PeriodAnswer.Unknown
    }
}
