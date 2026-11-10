package com.dynokore.platform.rulesevaluation.scope

import com.dynokore.platform.rulesevaluation.EvaluationResult

/** No scope rules are configured, so no verdict can be given; the caller never receives a default one. */
class ScopeRulesMissing : RuntimeException("no ScopeVerdictPort is configured")

/**
 * Fills [EvaluationResult.scopeVerdict] from the scope rules (INV-REQ-775: pure, no clock, no store). The verdict is
 * the port's answer as text, unchanged. No port is a refusal ([ScopeRulesMissing]); an exception of the port
 * propagates as the same instance; a default verdict is never invented.
 */
class ScopeEvaluation(
    private val port: ScopeVerdictPort?,
) {
    fun scopeVerdictOf(facts: ScopeFacts): String = (port ?: throw ScopeRulesMissing()).verdictOf(facts).name

    fun fill(
        result: EvaluationResult,
        facts: ScopeFacts,
    ): EvaluationResult = result.copy(scopeVerdict = scopeVerdictOf(facts))
}
