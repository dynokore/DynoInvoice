package com.dynokore.gst.tenancy.domain

import java.time.LocalDate

/** The Excluded Business determination of an entity (INV-REQ-331). */
enum class ExcludedBusinessStatus {
    NOT_EXCLUDED,
    OVERSEAS_S33_1,
    OVR,
    RC_ONLY,
    ;

    val excluded: Boolean get() = this != NOT_EXCLUDED
}

/** The three effective-dated status attributes of a registered entity (INV-REQ-331). */
enum class StatusKind { EXCLUDED_BUSINESS, PARTIAL_EXEMPTION, OBLIGATION_START }

/** The option keys of the implementation-date basis (INV-REQ-1312). */
enum class ObligationStartBasis { MANDATORY_DATE, EARLIER_VOLUNTARY_DATE, IRAS_COMMUNICATED_DATE }

/** One effective period of one status attribute: the value in force from [effectiveFrom] until the next entry. */
interface Effective {
    val effectiveFrom: LocalDate
}

object StatusTimeline {
    /**
     * The entry in force on [date]: the latest entry effective on or before it, so a purchase dated before a status
     * takes effect is read by the status that held on its transaction date (INV-AC-1552). Null when none was in force.
     */
    fun <T : Effective> inForceOn(
        history: List<T>,
        date: LocalDate,
    ): T? = history.filter { !it.effectiveFrom.isAfter(date) }.maxByOrNull { it.effectiveFrom }
}
