package com.dynokore.platform.rulesevaluation

/**
 * The result of evaluating one document's line, as the composed evaluation returns it. This slice fills nothing of
 * it; the scope verdict and the document type code are filled by the later slices, and stay null until then.
 */
data class EvaluationResult(
    val categoryCode: String?,
    val scopeVerdict: String?,
    val documentTypeCode: String?,
)
