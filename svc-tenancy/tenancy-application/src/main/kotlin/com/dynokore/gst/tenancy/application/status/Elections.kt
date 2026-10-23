package com.dynokore.gst.tenancy.application.status

import java.time.LocalDate

/** The implementation-date basis of VP-51 resolved for an entity on a date (INV-REQ-1312). */
sealed interface ResolvedImplementation {
    /**
     * The mandatory date of the latest stored assessPhase result. [recorded] is false when no VP-51 value is set for the
     * entity or its group (the catalogue default applies).
     */
    data class MandatoryDate(
        val recorded: Boolean,
    ) : ResolvedImplementation

    data class EarlierVoluntaryDate(
        val date: LocalDate,
    ) : ResolvedImplementation

    /** The date IRAS communicated for a GST group or division, with its reference. */
    data class IrasCommunicatedDate(
        val date: LocalDate,
        val irasReference: String,
    ) : ResolvedImplementation
}

/** Reads VP-51 through config-resolver's PolicyReader for the entity and its parent group (entity beats group). */
fun interface ImplementationBasisReader {
    fun resolve(
        groupRef: String,
        entityRef: String,
        on: LocalDate,
    ): ResolvedImplementation
}

/** Reads VP-64 (INV-REQ-1547) of a GST group through PolicyReader: does it transmit an excluded member's data. */
fun interface GroupElectionReader {
    fun transmitsExcludedMember(
        groupRef: String,
        on: LocalDate,
    ): Boolean
}
