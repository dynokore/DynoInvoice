package com.dynokore.gst.rules.domain.pack

/**
 * One entry of a pack's content, reduced to what the registry checks: its identity, its source citation and its
 * source class (INV-REQ-727, INV-REQ-774). An entry that arrives without a class is unclassified and is never read as
 * `PRIMARY` (coordinator ruling INV-Q-6477), so both fields are nullable here and the check below refuses the gap.
 */
data class PackEntry(
    val entryId: String,
    val citation: String?,
    val sourceClass: SourceClass?,
) {
    init {
        require(entryId.isNotBlank()) { "entryId must not be blank" }
    }
}

/** Why an entry was refused at import (INV-AC-1727: the pack is refused, naming the entry). */
data class EntryFinding(
    val entryId: String,
    val problem: String,
)

sealed interface EntryCheck {
    /** Every entry carries what its authority requires; the accepted entries show authority, citation and class. */
    data class Accepted(
        val authority: PackAuthority,
        val entries: List<PackEntry>,
    ) : EntryCheck

    /** The pack is refused (INV-AC-1727). */
    data class Refused(
        val findings: List<EntryFinding>,
    ) : EntryCheck
}

/**
 * The completeness check rules-svc runs at pack import and dry run (INV-REQ-727, INV-REQ-774, INV-AC-1727): every entry
 * carries a source class; every entry of an IRAS-derived pack also carries a non-blank source citation. An empty pack
 * has nothing to cite and is refused too, because a pack with no entries cannot show its sources.
 */
object PackEntryCompleteness {
    fun check(
        authority: PackAuthority,
        entries: List<PackEntry>,
    ): EntryCheck {
        if (entries.isEmpty()) {
            return EntryCheck.Refused(listOf(EntryFinding("*", "the pack has no entries")))
        }
        val findings =
            entries.flatMap { entry ->
                buildList {
                    if (entry.sourceClass == null) add(EntryFinding(entry.entryId, "no source class"))
                    if (authority.irasDerived && entry.citation.isNullOrBlank()) {
                        add(EntryFinding(entry.entryId, "no source citation"))
                    }
                }
            }
        return if (findings.isEmpty()) EntryCheck.Accepted(authority, entries) else EntryCheck.Refused(findings)
    }
}
