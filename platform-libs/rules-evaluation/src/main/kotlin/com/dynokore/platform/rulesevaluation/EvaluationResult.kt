package com.dynokore.platform.rulesevaluation

/**
 * The result of evaluating one document's line, as the composed evaluation returns it. `ScopeEvaluation` fills the
 * scope verdict (slice 2) with the verdict name exactly as the port gives it; the document type code is filled by
 * the last slice and stays null until then.
 */
data class EvaluationResult(
    val categoryCode: String?,
    val scopeVerdict: String?,
    val documentTypeCode: String?,
)
