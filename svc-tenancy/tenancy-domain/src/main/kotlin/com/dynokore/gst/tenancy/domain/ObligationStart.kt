package com.dynokore.gst.tenancy.domain

import java.time.LocalDate

/** The implementation date of an entity and the basis that supplied it (INV-REQ-1312, VP-51). */
data class ImplementationDate(
    val date: LocalDate,
    val basis: ObligationStartBasis,
    val irasReference: String? = null,
) {
    /** The communicated date of a GST group or division is an override of the phase table (INV-REQ-717). */
    val override: Boolean get() = basis == ObligationStartBasis.IRAS_COMMUNICATED_DATE
}

/** The obligation start with the inputs it was derived from, as recorded (INV-REQ-335). */
data class ObligationStart(
    val date: LocalDate,
    val implementation: ImplementationDate,
    val registrationStartDate: LocalDate?,
)

object ObligationStartRule {
    /**
     * The obligation start is the later of the registration start date and the implementation date (INV-REQ-335
     * behaviour 3, INV-AC-1626, INV-AC-1627). Without a registration start date it is the implementation date.
     */
    fun derive(
        registrationStartDate: LocalDate?,
        implementation: ImplementationDate,
    ): ObligationStart =
        ObligationStart(
            listOfNotNull(registrationStartDate, implementation.date).max(),
            implementation,
            registrationStartDate,
        )
}
