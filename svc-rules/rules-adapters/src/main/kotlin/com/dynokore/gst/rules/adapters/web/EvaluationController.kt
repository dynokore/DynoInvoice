package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.rules.adapters.packs.PackContentsReader
import com.dynokore.gst.rules.application.CodeDocumentCommand
import com.dynokore.gst.rules.application.DocumentCodingResult
import com.dynokore.gst.rules.application.DocumentLine
import com.dynokore.gst.rules.adapters.scope.ProfileScopeFacts
import com.dynokore.gst.rules.adapters.scope.ScopePackMissing
import com.dynokore.gst.rules.adapters.scope.VoluntaryOverSubmission
import com.dynokore.gst.rules.application.EvaluateDocument
import com.dynokore.gst.rules.contract.api.EvaluationApi
import com.dynokore.gst.rules.contract.model.EvaluateDocumentRequest
import com.dynokore.gst.rules.contract.model.EvaluationResult
import com.dynokore.gst.rules.contract.model.EvaluationResultSelfBillingAssignment
import com.dynokore.gst.rules.contract.model.PackVersionApplied
import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.domain.coding.Direction
import com.dynokore.gst.rules.domain.coding.LineCoder
import com.dynokore.gst.rules.domain.coding.LineCoding
import com.dynokore.gst.rules.domain.coding.LineInput
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.gst.rules.domain.scope.ScopeDirection
import com.dynokore.gst.rules.domain.scope.ScopeFacts
import com.dynokore.gst.rules.domain.scope.ScopeOutcome
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import com.dynokore.gst.rules.domain.scope.ScopeVerdict as DomainScopeVerdict

/**
 * The evaluation API of the rules contract (TPL-08): the same pure evaluation of one line for a recorded document
 * (`evaluateDocument`) and for a pre-issuance draft (`evaluatePreIssuanceDraft`, INV-Q-7442 A). The packs are the
 * ARMED versions in force on the resolution date, read from the holder copy (INV-Q-7438); nothing is written. A refused
 * line is an answer of 200 carrying the code and severity of the refusal (INV-AC-10201), never an error envelope.
 * Open, because Spring proxies a controller.
 */
@RestController
open class EvaluationController(
    private val evaluate: EvaluateDocument,
    private val packs: PackContentsReader,
    private val tenants: TenantContext,
    private val transactions: TransactionRunner,
    private val profile: ProfileScopeFacts,
    private val voluntary: VoluntaryOverSubmission,
) : EvaluationApi {
    override fun evaluateDocument(evaluateDocumentRequest: EvaluateDocumentRequest): ResponseEntity<EvaluationResult> =
        ResponseEntity.ok(evaluated(evaluateDocumentRequest))

    override fun evaluatePreIssuanceDraft(
        evaluateDocumentRequest: EvaluateDocumentRequest,
    ): ResponseEntity<EvaluationResult> = ResponseEntity.ok(evaluated(evaluateDocumentRequest))

    private fun evaluated(request: EvaluateDocumentRequest): EvaluationResult {
        val tenant = tenants.current()
        // one resolution date per document: the preceding document's for a linked credit note, which the caller
        // derives and passes in (INV-REQ-362 behavior 4); never derived here
        val date = TransactionDate(request.precedingDocumentDate ?: request.transactionDate)
        val input =
            LineInput(
                internalTaxCode = request.internalTaxCode,
                direction = request.direction?.let { Direction.valueOf(it.value) },
                election = request.election,
                counterpartyApprovedSchemes = request.counterpartySchemeData?.approvedSchemes?.toSet(),
            )
        return transactions.inTransaction {
            val result =
                evaluate.code(
                    CodeDocumentCommand(tenant, date, listOf(DocumentLine(LINE_ID, ZERO, ZERO, input))),
                )
            val coding = codingOf(result, tenant, date, input)
            val (outcome, answered) = scopeOutcomeOf(request, tenant, date, coding)
            answer(outcome, answered, result, versionsApplied(tenant, date))
        }
    }

    /**
     * The scope outcome of the line from the request's facts, the entity profile and the VP-21 election, all read
     * from replicas. With no SCOPE pack on the date the answer is a RUL-002 refusal, never a verdict.
     */
    private fun scopeOutcomeOf(
        request: EvaluateDocumentRequest,
        tenant: TenantId,
        date: TransactionDate,
        coding: LineCoding,
    ): Pair<ScopeOutcome, LineCoding> {
        val dates = profile.datesOf(tenant, request.entityRef, date)
        val facts =
            ScopeFacts(
                tenant = tenant,
                date = date,
                line = coding,
                direction = request.direction?.let { ScopeDirection.valueOf(it.value) } ?: ScopeDirection.SALE,
                sameGroup = request.sameGroup ?: false,
                inputTaxClaimedNonZero = request.inputTaxClaimedNonZero,
                voluntaryOverSubmission = voluntary.isOn(tenant, request.entityRef, date),
                obligationStartDate = dates.obligationStartDate,
                registrationEndDate = dates.registrationEndDate,
            )
        return try {
            evaluate.scopeOutcomeOf(facts) to coding
        } catch (_: ScopePackMissing) {
            // a refused line is never submitted; the refusal itself is the answer (INV-REQ-1089)
            ScopeOutcome(DomainScopeVerdict.NOT_SUBMITTABLE, disregardedSupplyWarning = false) to
                LineCoding.Refused(ErrorCode.RUL_002)
        }
    }

    /** The per-line coding the verdict is decided from: the document coding's refusal, else the coder's own line. */
    private fun codingOf(
        result: DocumentCodingResult,
        tenant: TenantId,
        date: TransactionDate,
        input: LineInput,
    ): LineCoding =
        when (result) {
            is DocumentCodingResult.Refused -> {
                LineCoding.Refused(result.code)
            }

            is DocumentCodingResult.LinesRefused -> {
                LineCoding.Refused(result.refusals.getValue(LINE_ID))
            }

            is DocumentCodingResult.Coded -> {
                LineCoder(
                    checkNotNull(packs.mappingAt(tenant, date)),
                    checkNotNull(packs.catalogueAt(tenant, date)),
                    checkNotNull(packs.scenarioAt(tenant, date)),
                ).code(input)
            }
        }

    private fun versionsApplied(
        tenant: TenantId,
        date: TransactionDate,
    ): List<PackVersionApplied> =
        listOf(PackTypes.MAPPING, PackTypes.CODE_CATALOGUE, PackTypes.SCENARIO).mapNotNull { type ->
            packs.versionAt(type, tenant, date)?.let { PackVersionApplied(type.value, it.version.value) }
        }

    private fun answer(
        outcome: ScopeOutcome,
        coding: LineCoding,
        result: DocumentCodingResult,
        versions: List<PackVersionApplied>,
    ): EvaluationResult {
        val scope = EvaluationResult.ScopeVerdict.valueOf(outcome.verdict.name)
        val warning = if (outcome.disregardedSupplyWarning) true else null
        return when (coding) {
            is LineCoding.Refused -> {
                EvaluationResult(
                    scopeVerdict = scope,
                    packVersionsApplied = versions,
                    severity = EvaluationResult.Severity.valueOf(coding.severity.name),
                    code = coding.code.code,
                    disregardedSupplyWarning = warning,
                )
            }

            is LineCoding.Coded -> {
                val line = (result as DocumentCodingResult.Coded).document.lines.single()
                EvaluationResult(
                    scopeVerdict = scope,
                    categoryCode = coding.categoryCode,
                    rate = line.rate,
                    ibt022Statement = coding.ibt022Statement,
                    disregardedSupplyWarning = warning,
                    packVersionsApplied = versions,
                    selfBillingAssignment =
                        coding.selfBillingAssignment?.let {
                            EvaluationResultSelfBillingAssignment(
                                EvaluationResultSelfBillingAssignment.Direction.valueOf(it.direction.name),
                                EvaluationResultSelfBillingAssignment.SubmissionType.valueOf(it.submissionType.name),
                            )
                        },
                )
            }
        }
    }

    private companion object {
        const val LINE_ID = "1"
        val ZERO: BigDecimal = BigDecimal.ZERO
    }
}
