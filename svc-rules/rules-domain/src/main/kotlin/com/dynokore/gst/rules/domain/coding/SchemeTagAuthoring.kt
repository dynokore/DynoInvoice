package com.dynokore.gst.rules.domain.coding

/** The result of checking the scheme tag of one authored mapping entry (INV-REQ-2444). */
sealed interface SchemeTagCheck {
    /** The tag, or no tag, is kept on the draft entry. */
    data object Accepted : SchemeTagCheck

    /**
     * The tag is refused and the draft entry is saved without it (INV-AC-2502). [codeSide] is the side of the entry's
     * IRAS code in the code catalogue, null when the catalogue does not hold the code.
     */
    data class Refused(
        val tag: String,
        val codeSide: Side?,
    ) : SchemeTagCheck
}

/**
 * Authoring check of a scheme tag against the sides the scenario pack declares for it (INV-REQ-2444 amended
 * 25 Sep 2026): the declared sides are compared with the side of the entry's IRAS code in the code catalogue, so a
 * scheme added by a pack release brings its own sides as data. Deny by default: a tag that no row of the pack lists,
 * and a code the catalogue does not hold, are refused. The self-billing marker is not a scheme tag and is not checked.
 */
object SchemeTagAuthoring {
    fun check(
        entry: MappingEntry,
        catalogue: CodeCatalogue,
        scenario: ScenarioData,
    ): SchemeTagCheck {
        val tag = entry.schemeTag
        return if (tag == null || tag == LineCoder.SELF_BILLING) {
            SchemeTagCheck.Accepted
        } else {
            val sideName = catalogue.entryOf(entry.irasCode)?.side
            val side = Side.entries.firstOrNull { it.name == sideName }
            if (side != null && scenario.schemeTagSides.any { it.tag == tag && it.side == side }) {
                SchemeTagCheck.Accepted
            } else {
                SchemeTagCheck.Refused(tag, side)
            }
        }
    }
}
