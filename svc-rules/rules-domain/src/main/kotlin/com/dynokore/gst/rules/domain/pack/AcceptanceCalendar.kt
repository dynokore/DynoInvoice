package com.dynokore.gst.rules.domain.pack

import java.time.LocalDate

/** One accepted specification version of an endpoint-acceptance pack (INV-REQ-589); both dates are inclusive. */
data class SpecAcceptance(
    val specVersion: String,
    val acceptedFrom: LocalDate,
    val acceptedTo: LocalDate? = null,
)

/** The acceptance calendar of an endpoint-acceptance pack (INV-AC-7967, INV-AC-1699): a pure function of its inputs. */
object AcceptanceCalendar {
    /**
     * The `specVersion` values accepted on [date]: `acceptedFrom <= date` and `acceptedTo` absent or
     * `date <= acceptedTo` (both ends inclusive). A version with no entry is never in the result.
     */
    fun acceptedOn(
        acceptances: List<SpecAcceptance>,
        date: LocalDate,
    ): Set<String> =
        acceptances
            .filter { !it.acceptedFrom.isAfter(date) && (it.acceptedTo == null || !date.isAfter(it.acceptedTo)) }
            .map { it.specVersion }
            .toSet()
}
