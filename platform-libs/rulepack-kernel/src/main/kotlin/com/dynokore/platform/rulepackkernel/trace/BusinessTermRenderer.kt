package com.dynokore.platform.rulepackkernel.trace

/**
 * How one rule reads to the finance user (INV-REQ-1182): a [title] and a sentence [template] whose
 * `{input}` placeholders take the step's inputs, and the words for each machine [verdictLabels] value.
 */
data class BusinessRule(
    val title: String,
    val template: String,
    val verdictLabels: Map<String, String> = emptyMap(),
)

/** The business vocabulary a pack ships for its rules and input names. */
data class BusinessTermCatalogue(
    val rules: Map<String, BusinessRule>,
    val inputLabels: Map<String, String> = emptyMap(),
)

data class RenderedTrace(
    /** One line per step, in trace order. */
    val lines: List<String>,
    /** Rule ids the catalogue has no business wording for; shown by id so nothing is hidden. */
    val untranslatedRules: List<String>,
)

/**
 * Renders a [DecisionTrace] in business terms (INV-REQ-1182 behaviour #3), never as only a failed assertion
 * identifier. A rule the catalogue lacks is rendered with its id and inputs and reported in
 * [RenderedTrace.untranslatedRules]; rendering never fails, because a console row must always show something.
 */
class BusinessTermRenderer(
    private val catalogue: BusinessTermCatalogue,
) {
    fun render(trace: DecisionTrace): RenderedTrace {
        val untranslated = mutableListOf<String>()
        val lines =
            trace.steps.map { step ->
                val rule = catalogue.rules[step.ruleId]
                val source = "pack ${step.packId} ${step.packVersion}"
                if (rule == null) {
                    untranslated += step.ruleId
                    "Rule ${step.ruleId} (no business description) on ${describeInputs(step.inputs)} " +
                        "gave ${step.verdict} ($source)"
                } else {
                    val verdict = rule.verdictLabels[step.verdict] ?: step.verdict
                    "${rule.title}: ${fill(rule.template, step.inputs)} Result: $verdict ($source)"
                }
            }
        return RenderedTrace(lines, untranslated.distinct())
    }

    private fun fill(
        template: String,
        inputs: Map<String, String>,
    ): String = PLACEHOLDER.replace(template) { match -> inputs[match.groupValues[1]] ?: NOT_RECORDED }

    private fun describeInputs(inputs: Map<String, String>): String =
        if (inputs.isEmpty()) {
            NOT_RECORDED
        } else {
            inputs.entries.joinToString(", ") { (name, value) -> "${catalogue.inputLabels[name] ?: name} = $value" }
        }

    private companion object {
        val PLACEHOLDER = Regex("\\{([^{}]+)}")
        const val NOT_RECORDED = "(not recorded)"
    }
}
