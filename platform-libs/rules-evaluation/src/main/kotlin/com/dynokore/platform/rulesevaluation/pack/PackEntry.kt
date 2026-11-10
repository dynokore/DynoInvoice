package com.dynokore.platform.rulesevaluation.pack

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
