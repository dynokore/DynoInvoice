package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.contract.model.PackVersionApplied
import com.dynokore.gst.rules.domain.scope.TraceEntry

/**
 * The `decisions` member of the evaluation `decisionTrace` (INV-REQ-942, INV-AC-20803): one entry in the shape of the
 * evidence contract's `DecisionTraceEntry` for a REFUSED line, and none for any other line (owner ruling 2026-11-18,
 * option A: only decisions that already have a code are listed). Pure; no code or rule id is ever synthesised.
 */
object DecisionEntries {
    const val KEY = "decisions"

    private val CODE = Regex("^[A-Z]{3}-[0-9]{3}$")
    private const val RULE_ID_MAX = 80
    private const val NAME_MAX = 100
    private const val VALUE_MAX = 500

    /**
     * The entries of a refused line: [refusalCode] is its refusal code; the rule id is the first of [scopeRules] (the
     * order of the outcome), else [fallbackRuleId] (the classifier's id for a classification refusal), else none.
     * An entry that would break a member of the shape (code pattern, rule id length, input name or value length,
     * no pack version) is not created. A pack version's `packIdentifier` is the one of the answer's item, else the pack
     * type value (the items of the classification and scope versions carry no pack id).
     */
    fun of(
        refusalCode: String?,
        scopeRules: List<TraceEntry>,
        fallbackRuleId: String?,
        versions: List<PackVersionApplied>,
    ): List<Map<String, Any>> {
        val first = scopeRules.firstOrNull()
        val ruleId = first?.rule ?: fallbackRuleId
        val inputs = first?.inputs.orEmpty()
        val valid =
            refusalCode != null && CODE.matches(refusalCode) &&
                ruleId != null && ruleId.length in 1..RULE_ID_MAX &&
                versions.isNotEmpty() &&
                inputs.all { (name, value) -> name.length in 1..NAME_MAX && value.length <= VALUE_MAX }
        return if (valid) {
            listOf(
                mapOf(
                    "code" to checkNotNull(refusalCode),
                    "ruleId" to checkNotNull(ruleId),
                    "packVersions" to
                        versions.map {
                            mapOf(
                                "packIdentifier" to (it.packIdentifier ?: it.packType),
                                "version" to it.version,
                            )
                        },
                    "inputs" to inputs.map { (name, value) -> mapOf("name" to name, "value" to value) },
                ),
            )
        } else {
            emptyList()
        }
    }

    /** [base] keeps every key it has; `decisions` is added only for a non-empty list and never overwrites a key. */
    fun traceWith(
        base: Map<String, Any>?,
        entries: List<Map<String, Any>>,
    ): Map<String, Any>? {
        if (entries.isEmpty()) return base
        check(base == null || KEY !in base) { "the line trace already holds the key $KEY" }
        return base.orEmpty() + (KEY to entries)
    }
}
