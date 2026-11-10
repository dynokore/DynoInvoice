package com.dynokore.gst.rules.application

import com.dynokore.gst.rules.domain.dryrun.DocumentOutcome
import com.dynokore.gst.rules.domain.dryrun.SampledDocument
import com.dynokore.gst.rules.domain.resolution.PackLookup
import com.dynokore.gst.rules.domain.resolution.PackResolver
import com.dynokore.gst.rules.domain.resolution.ResolutionDates
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersion
import com.dynokore.platform.rulepackkernel.evaluation.EvaluationInputs
import com.dynokore.platform.rulepackkernel.evaluation.Evaluator
import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/** The version of [packType] named by [label] for [tenant], or null. */
fun interface NamedPackVersions {
    fun find(
        tenant: TenantId,
        packType: PackType,
        label: String,
    ): PackVersion?
}

/**
 * Production [PackEvaluationPort]: the evaluation of INV-T-4966 ([EvaluateDocument]) with the pack version named by the
 * call in place of the one in force on the document's date. The other pack types resolve by date as usual.
 */
class PackDocumentEvaluation(
    private val named: NamedPackVersions,
    private val byDate: PackLookup,
    private val evaluator: Evaluator,
    private val packTypes: List<PackType>,
) : PackEvaluationPort {
    override fun evaluate(
        tenant: TenantId,
        packType: String,
        packVersion: String,
        document: SampledDocument,
    ): DocumentOutcome {
        val type = typeOf(packType)
        val lookup =
            PackLookup { t, tn, date ->
                if (t == type) named.find(tenant, type, packVersion) else byDate.resolve(t, tn, date)
            }
        val useCase = EvaluateDocument(PackResolver(lookup), evaluator, packTypes)
        val dates =
            ResolutionDates(
                TransactionDate(document.transactionDate),
                document.precedingDocumentDate?.let(::TransactionDate),
            )
        val inputs = EvaluationInputs.of(mapOf("internalTaxCode" to document.internalTaxCode))
        return when (val result = useCase.handle(EvaluateCommand(tenant, dates, inputs))) {
            is EvaluateResult.Decided -> DocumentOutcome(result.evaluation.verdict)
            is EvaluateResult.Refused -> DocumentOutcome(result.code.code, result.code.severity.name)
            EvaluateResult.NoRuleFired -> error("no rule fired for document ${document.docUuid}")
            is EvaluateResult.Conflict -> error("rules conflict for document ${document.docUuid}")
        }
    }

    override fun effectiveFrom(
        tenant: TenantId,
        packType: String,
        packVersion: String,
    ): LocalDate =
        checkNotNull(named.find(tenant, typeOf(packType), packVersion)) { "pack version $packVersion not found" }
            .manifest.effectiveFrom

    private fun typeOf(name: String): PackType =
        packTypes.firstOrNull { it.value == name } ?: error("unknown pack type $name")
}
