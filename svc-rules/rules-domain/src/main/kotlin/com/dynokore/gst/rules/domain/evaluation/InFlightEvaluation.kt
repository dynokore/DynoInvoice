package com.dynokore.gst.rules.domain.evaluation

import com.dynokore.gst.rules.domain.resolution.PackBinding
import com.dynokore.platform.rulepackkernel.evaluation.EvaluationInputs
import com.dynokore.platform.rulepackkernel.evaluation.EvaluationOutcome
import com.dynokore.platform.rulepackkernel.evaluation.Evaluator

/**
 * An evaluation that has started (INV-REQ-1167): it holds the binding resolved at its start and completes under that
 * binding whatever is armed meanwhile. Only a new [InFlightEvaluation], made from a fresh resolution, sees a newer
 * pack.
 */
class InFlightEvaluation(
    private val binding: PackBinding,
    private val inputs: EvaluationInputs,
) {
    fun complete(evaluator: Evaluator): EvaluationOutcome = evaluator.evaluate(binding.applied, inputs)
}
