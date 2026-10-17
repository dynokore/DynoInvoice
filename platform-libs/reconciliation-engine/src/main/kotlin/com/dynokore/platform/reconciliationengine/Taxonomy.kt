package com.dynokore.platform.reconciliationengine

import java.math.BigDecimal

/** A category of the host's difference taxonomy; the code is opaque to this library. */
data class Category(
    val code: String,
    val label: String,
) {
    init {
        require(code.isNotBlank()) { "A category code must not be blank" }
    }
}

/** A host-supplied evidence record for a key: a reference the work-paper links to, and an amount. */
interface EvidenceRecord {
    val reference: String
    val amount: BigDecimal
}

/**
 * Explains a variance on [key] (key equality, never prefix or pattern) when [matches] accepts records for that key.
 * The explained amount is the sum of the amounts of the records [matches] accepts; [linkedRecords] names the
 * references the work-paper links for one accepted record.
 */
class ClassificationRule(
    val category: Category,
    val key: String,
    val matches: (EvidenceRecord) -> Boolean,
    val linkedRecords: (EvidenceRecord) -> List<String> = { listOf(it.reference) },
) {
    init {
        requireValidKey(key)
    }
}

/**
 * The taxonomy as data. Construction throws [IllegalArgumentException] for a rule whose category code is not supplied.
 */
class RuleSet(
    val categories: Set<Category>,
    val rules: List<ClassificationRule>,
) {
    init {
        val codes = categories.map { it.code }.toSet()
        for (rule in rules) {
            require(rule.category.code in codes) { "Rule category '${rule.category.code}' is not in the category set" }
        }
    }

    fun forKey(key: String): List<ClassificationRule> = rules.filter { it.key == key }

    companion object {
        fun empty(): RuleSet = RuleSet(emptySet(), emptyList())
    }
}
