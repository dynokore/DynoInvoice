package com.dynokore.platform.rulesevaluation.packs

import com.dynokore.platform.rulesevaluation.scope.ScopeVerdict

/**
 * Decodes the scope sections of a SCOPE pack that the scope rules read (`scope-pack-contents.schema.json`):
 * `lineScopeRules` and `intraGroupRule.treatment`. The classification table is read by a later slice; a member the
 * schema does not name is ignored (API-41).
 */
object ScopePackContents {
    private const val PACK_TYPE = "SCOPE"

    /** What the scope rules read from a SCOPE pack: the verdict per category code and the intra-group treatment. */
    data class ScopeData(
        val lineVerdicts: Map<String, ScopeVerdict>,
        val intraGroupTreatment: String?,
    )

    private val LINE_VERDICTS = listOf(ScopeVerdict.IN_SCOPE, ScopeVerdict.OUT_OF_SCOPE, ScopeVerdict.EXCLUDED_TXN)
    private val LINE_VERDICT_NAMES = LINE_VERDICTS.map { it.name }.toSet()

    /**
     * Decodes `lineScopeRules` (INV-REQ-493, INV-REQ-496) and `intraGroupRule.treatment` (INV-REQ-738). A `lineVerdict`
     * outside IN_SCOPE, OUT_OF_SCOPE and EXCLUDED_TXN fails; a category code twice fails; a missing `intraGroupRule`
     * or `treatment` decodes to an absent treatment, which the scope rules deny (fail closed).
     */
    fun decodeScopeData(contents: ByteArray): ScopeData {
        val root = ContentsNode.root(PACK_TYPE, contents)
        val rules = root.array("lineScopeRules", min = 1, max = 500)
        val verdicts = LinkedHashMap<String, ScopeVerdict>()
        rules.forEach { rule ->
            val code = rule.text("categoryCode", pattern = CODE_PATTERN)
            val verdict = ScopeVerdict.valueOf(rule.text("lineVerdict", oneOf = LINE_VERDICT_NAMES))
            if (verdicts.put(code, verdict) != null) {
                root.fail("lineScopeRules", "holds the category code '$code' twice")
            }
        }
        val treatment = root.member("intraGroupRule")?.let { root.obj("intraGroupRule").optionalText("treatment") }
        return ScopeData(verdicts, treatment)
    }
}
