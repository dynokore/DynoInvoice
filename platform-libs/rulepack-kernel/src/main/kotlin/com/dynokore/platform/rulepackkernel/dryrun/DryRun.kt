package com.dynokore.platform.rulepackkernel.dryrun

import com.dynokore.platform.rulepackkernel.trace.DecisionTrace
import java.math.BigDecimal
import java.util.TreeMap

/** One document of the sample. [straddlesChange] marks a credit note that straddles the change (INV-REQ-703). */
data class SampleDocument(
    val id: String,
    val period: String,
    val inputs: Map<String, String>,
    val straddlesChange: Boolean = false,
)

/** A verdict as the delta compares it (INV-REQ-772): the code, the taxable value and the GST value, with its trace. */
data class Verdict(
    val code: String,
    val taxableValue: BigDecimal,
    val gstValue: BigDecimal,
    val trace: DecisionTrace,
) {
    /** Equal in outcome: same code and the same amounts, ignoring scale (2.0 equals 2.00) and the trace. */
    fun sameOutcomeAs(other: Verdict): Boolean =
        code == other.code &&
            taxableValue.compareTo(other.taxableValue) == 0 &&
            gstValue.compareTo(other.gstValue) == 0
}

/** Evaluates a document under one pack version. Must be pure (INV-REQ-775): same document, same verdict. */
fun interface DocumentEvaluator {
    fun evaluate(document: SampleDocument): Verdict
}

enum class Side { CURRENT, CANDIDATE }

data class DocumentChange(
    val document: SampleDocument,
    val before: Verdict,
    val after: Verdict,
)

data class EvaluationFailure(
    val documentId: String,
    val side: Side,
    val message: String,
)

data class ValueDelta(
    val taxable: BigDecimal,
    val gst: BigDecimal,
)

/**
 * The dry-run delta of a candidate pack over a document sample (INV-REQ-772, INV-REQ-686, INV-REQ-703,
 * INV-REQ-1352): every changed document with its old and new verdict, reported by period, the straddling
 * credit notes listed separately. Documents that could not be evaluated are listed in [failures], never
 * counted as unchanged.
 */
data class DryRunDelta(
    val sampled: Int,
    val unchanged: Int,
    val changes: List<DocumentChange>,
    val failures: List<EvaluationFailure>,
) {
    val straddling: List<DocumentChange> get() = changes.filter { it.document.straddlesChange }
    val ordinary: List<DocumentChange> get() = changes.filterNot { it.document.straddlesChange }

    /** Changed documents per period, ordered by period; straddling credit notes excluded (reported on their own). */
    val changedByPeriod: Map<String, Int> get() = ordinary.groupingBy { it.document.period }.eachCount().toSortedMap()

    /** The movement in taxable and GST value per period (after minus before), ordered by period. */
    val valueDeltaByPeriod: Map<String, ValueDelta>
        get() {
            val totals = TreeMap<String, ValueDelta>()
            ordinary.forEach { change ->
                val movement =
                    ValueDelta(
                        change.after.taxableValue - change.before.taxableValue,
                        change.after.gstValue - change.before.gstValue,
                    )
                totals.merge(change.document.period, movement) { a, b ->
                    ValueDelta(a.taxable + b.taxable, a.gst + b.gst)
                }
            }
            return totals
        }
}

/**
 * Runs the dry run: pure over the sample it is given, no I/O, so a private deployment's data never
 * leaves (INV-REQ-1081).
 */
object DryRun {
    fun run(
        sample: List<SampleDocument>,
        current: DocumentEvaluator,
        candidate: DocumentEvaluator,
    ): DryRunDelta {
        require(sample.isNotEmpty()) { "a dry run needs a non-empty sample" }
        val changes = mutableListOf<DocumentChange>()
        val failures = mutableListOf<EvaluationFailure>()
        var unchanged = 0
        sample.forEach { document ->
            val before = attempt(document, Side.CURRENT, current, failures)
            val after = attempt(document, Side.CANDIDATE, candidate, failures)
            when {
                before == null || after == null -> Unit
                before.sameOutcomeAs(after) -> unchanged++
                else -> changes += DocumentChange(document, before, after)
            }
        }
        return DryRunDelta(sample.size, unchanged, changes, failures)
    }

    private fun attempt(
        document: SampleDocument,
        side: Side,
        evaluator: DocumentEvaluator,
        failures: MutableList<EvaluationFailure>,
    ): Verdict? =
        try {
            evaluator.evaluate(document)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            failures += EvaluationFailure(document.id, side, e::class.simpleName ?: "failure")
            null
        }
}
