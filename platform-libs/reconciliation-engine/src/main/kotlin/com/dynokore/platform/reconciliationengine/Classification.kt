package com.dynokore.platform.reconciliationengine

import java.math.BigDecimal

/** The part of a variance one rule explained, with the records linked. */
data class ClassifiedVariance(
    val key: String,
    val category: Category,
    val amount: BigDecimal,
    val linkedRecords: List<String>,
)

/** A comparison line with its classification: `unexplainedResidual = variance - sum(explained amounts)`. */
data class LineResult(
    val comparison: Comparison,
    val classified: List<ClassifiedVariance>,
    val unexplainedResidual: BigDecimal,
) {
    /** Exactly when the residual is zero by `compareTo`, not `equals`. */
    val fullyExplained: Boolean get() = unexplainedResidual.compareTo(BigDecimal.ZERO) == 0
}

/** A line whose residual is not zero. Never clamped, never hidden by a tolerance. */
data class UnexplainedLine(
    val key: String,
    val variance: BigDecimal,
    val residual: BigDecimal,
)

data class ReconciliationResult(
    val lines: List<LineResult>,
) {
    val comparison: List<Comparison> get() = lines.map { it.comparison }
    val classifiedVariances: List<ClassifiedVariance> get() = lines.flatMap { it.classified }
    val unexplained: List<UnexplainedLine> get() =
        lines.filter { !it.fullyExplained }.map {
            UnexplainedLine(it.comparison.key, it.comparison.variance, it.unexplainedResidual)
        }
    val hasUnexplained: Boolean get() = unexplained.isNotEmpty()
}

/** Applies a [RuleSet] to comparison lines. There is no tolerance parameter. */
object Classifier {
    fun classify(
        lines: List<Comparison>,
        ruleSet: RuleSet,
        evidence: Map<String, List<EvidenceRecord>>,
    ): ReconciliationResult =
        ReconciliationResult(
            lines.map { line ->
                if (line.variance.compareTo(BigDecimal.ZERO) == 0) {
                    LineResult(line, emptyList(), line.variance)
                } else {
                    val records = evidence[line.key].orEmpty()
                    val classified =
                        ruleSet.forKey(line.key).mapNotNull { rule ->
                            val accepted = records.filter(rule.matches)
                            if (accepted.isEmpty()) {
                                null
                            } else {
                                ClassifiedVariance(
                                    line.key,
                                    rule.category,
                                    accepted.fold(BigDecimal.ZERO) { acc, r -> acc.add(r.amount) },
                                    accepted.flatMap(rule.linkedRecords),
                                )
                            }
                        }
                    val explained = classified.fold(BigDecimal.ZERO) { acc, c -> acc.add(c.amount) }
                    LineResult(line, classified, line.variance.subtract(explained))
                }
            },
        )
}
