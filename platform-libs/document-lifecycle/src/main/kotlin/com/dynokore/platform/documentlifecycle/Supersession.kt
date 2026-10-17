package com.dynokore.platform.documentlifecycle

/**
 * Supersession as data: when a version moves into [trigger], every older version of the same document that is in a
 * state of [older] moves to [becomes] in the same returned document. Older means a lower version number.
 */
data class SupersessionRule(
    val trigger: String,
    val older: Set<String>,
    val becomes: String,
)

/**
 * The rules of one [StateModel]. Construction throws [IllegalArgumentException] unless every state named is in the
 * model and every moved pair (an `older` state to `becomes`) is an allowed transition of the model.
 */
class SupersessionRules(
    val model: StateModel,
    val rules: List<SupersessionRule>,
) {
    init {
        for (rule in rules) {
            require(model.contains(rule.trigger)) { "Rule trigger '${rule.trigger}' is not a state of the model" }
            require(model.contains(rule.becomes)) { "Rule target '${rule.becomes}' is not a state of the model" }
            for (state in rule.older) {
                require(model.allows(state, rule.becomes)) {
                    "Rule on '${rule.trigger}' moves '$state' to '${rule.becomes}', which is not an allowed transition"
                }
            }
        }
    }

    fun triggeredBy(state: String): List<SupersessionRule> = rules.filter { it.trigger == state }

    companion object {
        fun none(model: StateModel): SupersessionRules = SupersessionRules(model, emptyList())
    }
}
