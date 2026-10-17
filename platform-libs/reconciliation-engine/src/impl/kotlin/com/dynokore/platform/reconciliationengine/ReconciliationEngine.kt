package com.dynokore.platform.reconciliationengine

import java.math.BigDecimal

/**
 * What an embedding service instantiates: its taxonomy as data. Holds no category, tolerance, persistence, clock or
 * event type; raising an alert for a result with unexplained lines is the service's.
 */
class ReconciliationEngine(
    val ruleSet: RuleSet = RuleSet.empty(),
) {
    constructor(categories: Set<Category>, rules: List<ClassificationRule>) : this(RuleSet(categories, rules))

    fun reconcile(
        returnSide: Map<String, BigDecimal>,
        submittedSide: Map<String, BigDecimal>,
        evidence: Map<String, List<EvidenceRecord>> = emptyMap(),
    ): ReconciliationResult = Classifier.classify(Comparisons.compare(returnSide, submittedSide), ruleSet, evidence)
}
