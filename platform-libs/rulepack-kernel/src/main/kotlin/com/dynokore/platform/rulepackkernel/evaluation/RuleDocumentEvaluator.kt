package com.dynokore.platform.rulepackkernel.evaluation

import com.dynokore.platform.rulepackkernel.dryrun.DocumentEvaluator
import com.dynokore.platform.rulepackkernel.dryrun.SampleDocument
import com.dynokore.platform.rulepackkernel.dryrun.Verdict
import java.math.BigDecimal

/** The input key of the stated taxable value of a sample document (property name of the recorded payload). */
const val TAXABLE_VALUE_KEY = "taxableValue"

/** The input key of the stated GST value of a sample document. */
const val GST_VALUE_KEY = "gstValue"

/** No rule fired for the document: there is no verdict and no default one (INV-Q-19133 A). */
class NoRuleFiredException(
    documentId: String,
) : IllegalStateException("no rule fired for document $documentId")

/** Fired rules disagree on the document (INV-Q-19135 B): fail closed, no verdict. */
class RuleConflictException(
    documentId: String,
    ruleIds: List<String>,
) : IllegalStateException("rules disagree for document $documentId: ${ruleIds.joinToString(",")}")

/**
 * A [DocumentEvaluator] over an [Evaluator] and the applied pack versions (INV-REQ-775, INV-REQ-772). The code is the
 * verdict string; the amounts are the ones the document states, never recomputed (INV-REQ-810). A document it cannot
 * evaluate fails: [IllegalArgumentException] for a missing or malformed amount, [NoRuleFiredException], and
 * [RuleConflictException]; none is caught here, so the dry run lists it as a failure.
 */
class RuleDocumentEvaluator(
    private val evaluator: Evaluator,
    private val applied: List<AppliedPack>,
) : DocumentEvaluator {
    override fun evaluate(document: SampleDocument): Verdict {
        val taxable = statedAmount(document, TAXABLE_VALUE_KEY)
        val gst = statedAmount(document, GST_VALUE_KEY)
        return when (val outcome = evaluator.evaluate(applied, EvaluationInputs.of(document.inputs))) {
            is EvaluationOutcome.Decided -> {
                Verdict(outcome.evaluation.verdict, taxable, gst, outcome.evaluation.trace)
            }

            EvaluationOutcome.NoRuleFired -> {
                throw NoRuleFiredException(document.id)
            }

            is EvaluationOutcome.Conflict -> {
                throw RuleConflictException(document.id, outcome.ruleIds)
            }
        }
    }

    private fun statedAmount(
        document: SampleDocument,
        key: String,
    ): BigDecimal {
        val text =
            requireNotNull(document.inputs[key]) { "document ${document.id} states no $key" }
        require(AMOUNT.matches(text)) { "document ${document.id} states a malformed $key" }
        return BigDecimal(text)
    }

    private companion object {
        /** ASCII digits only, the whole text (the pattern of the sample payload schema). */
        val AMOUNT = Regex("-?[0-9]{1,17}(\\.[0-9]{1,2})?")
    }
}
