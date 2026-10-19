package com.dynokore.gst.rules.application

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.domain.evaluation.Evaluation
import com.dynokore.gst.rules.domain.evaluation.EvaluationInputs
import com.dynokore.gst.rules.domain.evaluation.EvaluationOutcome
import com.dynokore.gst.rules.domain.evaluation.Evaluator
import com.dynokore.gst.rules.domain.evaluation.InFlightEvaluation
import com.dynokore.gst.rules.domain.resolution.PackResolution
import com.dynokore.gst.rules.domain.resolution.PackResolver
import com.dynokore.gst.rules.domain.resolution.ResolutionDates
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.tenancykernel.TenantId

/** The request of one evaluation: whose packs, which dates and the explicit inputs (INV-REQ-770, INV-REQ-775). */
data class EvaluateCommand(
    val tenant: TenantId?,
    val dates: ResolutionDates,
    val inputs: EvaluationInputs,
)

sealed interface EvaluateResult {
    /** The verdict, the decision trace and the pack versions applied, for the caller to record (INV-REQ-771, 777). */
    data class Decided(
        val evaluation: Evaluation,
    ) : EvaluateResult

    /** No rule fired for the inputs: no verdict, so no trace; what that means is the specialised evaluation's. */
    data object NoRuleFired : EvaluateResult

    /** No pack was effective on the date: RUL-002 (INV-REQ-1089), and nothing was evaluated against another pack. */
    data class Refused(
        val code: ErrorCode,
        val packType: PackType,
        val date: TransactionDate,
    ) : EvaluateResult
}

/**
 * The evaluation core of rules-svc (INV-FLOW-240, INV-FLOW-241): resolve the packs by the document's dates, fix the
 * binding, evaluate purely under it. It reads no clock and writes nothing: the caller records the pack versions and
 * carries the trace (INV-REQ-777 behaviour 2).
 */
class EvaluateDocument(
    private val resolver: PackResolver,
    private val evaluator: Evaluator,
    private val packTypes: List<PackType>,
) {
    fun handle(command: EvaluateCommand): EvaluateResult =
        when (val resolution = resolver.resolve(packTypes, command.tenant, command.dates)) {
            is PackResolution.NoPackEffective ->
                EvaluateResult.Refused(resolution.code, resolution.packType, resolution.date)

            is PackResolution.Resolved ->
                when (val outcome = InFlightEvaluation(resolution.binding, command.inputs).complete(evaluator)) {
                    is EvaluationOutcome.Decided -> EvaluateResult.Decided(outcome.evaluation)
                    EvaluationOutcome.NoRuleFired -> EvaluateResult.NoRuleFired
                }
        }
}
