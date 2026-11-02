package com.dynokore.gst.rules.domain.pack

import java.time.LocalDate

/** A mapping entry of a draft as the acceptance reads it; the entry id is the internal tax code. */
data class MappingDraftEntry(
    val entryId: String,
    val irasCode: String,
    val sourceClass: SourceClass,
) {
    init {
        require(entryId.isNotBlank()) { "entryId must not be blank" }
    }
}

/**
 * The explicit acceptance of one non-PRIMARY entry (INV-REQ-729): scoped to the entry id, with the IRAS code and the
 * source class it was given for, a rationale and an expiry date.
 */
data class MappingAcceptanceRecord(
    val entryId: String,
    val rationale: String,
    val expiresOn: LocalDate,
    val acceptedIrasCode: String,
    val acceptedSourceClass: SourceClass,
    val acceptedBy: String,
)

/**
 * Which entries an acceptance releases at a sign-off (INV-REQ-683, INV-REQ-728, INV-REQ-729, INV-AC-756). An acceptance
 * belongs to the draft it was recorded in and nothing copies it to another version, so it ends at the next sign-off.
 */
object MappingAcceptance {
    const val MAX_RATIONALE = 500

    fun rationaleValid(rationale: String): Boolean = rationale.isNotBlank() && rationale.length <= MAX_RATIONALE

    fun acceptedEntryIds(
        entries: List<MappingDraftEntry>,
        acceptances: List<MappingAcceptanceRecord>,
        asOf: LocalDate,
    ): Set<String> {
        val byEntry = acceptances.groupBy { it.entryId }
        return entries
            .filter { entry ->
                entry.sourceClass != SourceClass.PRIMARY &&
                    byEntry[entry.entryId].orEmpty().any { valid(it, entry, asOf) }
            }.map { it.entryId }
            .toSet()
    }

    private fun valid(
        acceptance: MappingAcceptanceRecord,
        entry: MappingDraftEntry,
        asOf: LocalDate,
    ): Boolean =
        rationaleValid(acceptance.rationale) &&
            acceptance.expiresOn.isAfter(asOf) &&
            acceptance.acceptedIrasCode == entry.irasCode &&
            acceptance.acceptedSourceClass == entry.sourceClass
}
