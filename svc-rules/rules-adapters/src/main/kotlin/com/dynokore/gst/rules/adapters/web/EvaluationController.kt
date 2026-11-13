package com.dynokore.gst.rules.adapters.web

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.adapters.packs.PackContentsReader
import com.dynokore.gst.rules.adapters.scope.ProfileScopeFacts
import com.dynokore.gst.rules.adapters.scope.ScopePackMissing
import com.dynokore.gst.rules.adapters.scope.VoluntaryOverSubmission
import com.dynokore.gst.rules.application.ClassifyCommand
import com.dynokore.gst.rules.application.ClassifyDocument
import com.dynokore.gst.rules.application.ClassifyResult
import com.dynokore.gst.rules.application.CodeCreditNoteCommand
import com.dynokore.gst.rules.application.CodeDocumentCommand
import com.dynokore.gst.rules.application.CreditNoteCoding
import com.dynokore.gst.rules.application.CreditNoteCodingResult
import com.dynokore.gst.rules.application.DocumentCoder
import com.dynokore.gst.rules.application.DocumentCodingResult
import com.dynokore.gst.rules.application.DocumentLine
import com.dynokore.gst.rules.application.EvaluateDocument
import com.dynokore.gst.rules.application.RateDateTrace
import com.dynokore.gst.rules.contract.api.EvaluationApi
import com.dynokore.gst.rules.contract.model.EvaluateDocumentRequest
import com.dynokore.gst.rules.contract.model.EvaluationResult
import com.dynokore.gst.rules.contract.model.EvaluationResultSelfBillingAssignment
import com.dynokore.gst.rules.contract.model.PackVersionApplied
import com.dynokore.gst.rules.domain.classification.Classification
import com.dynokore.gst.rules.domain.classification.ClassificationTraceEntry
import com.dynokore.gst.rules.domain.classification.ClassifiedLine
import com.dynokore.gst.rules.domain.classification.ClassifyInput
import com.dynokore.gst.rules.domain.classification.DocumentClassifier
import com.dynokore.gst.rules.domain.classification.DocumentFunction
import com.dynokore.gst.rules.domain.classification.SignOfTotal
import com.dynokore.gst.rules.domain.coding.Direction
import com.dynokore.gst.rules.domain.coding.ELECTED_SCHEMES
import com.dynokore.gst.rules.domain.coding.ELECTION
import com.dynokore.gst.rules.domain.coding.LineCoder
import com.dynokore.gst.rules.domain.coding.LineCoding
import com.dynokore.gst.rules.domain.coding.LineInput
import com.dynokore.gst.rules.domain.coding.NO_ELECTION
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.gst.rules.domain.scope.ScopeOutcome
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
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
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass") // one pure evaluation; each collaborator is a port
open class EvaluationController(
    private val evaluate: EvaluateDocument,
    private val packs: PackContentsReader,
    private val tenants: TenantContext,
    private val transactions: TransactionRunner,
    private val profile: ProfileScopeFacts,
    private val voluntary: VoluntaryOverSubmission,
    private val classify: ClassifyDocument,
) : EvaluationApi {
    private val creditNotes = CreditNoteCoding(DocumentCoder(packs, packs, packs))

    override fun evaluateDocument(evaluateDocumentRequest: EvaluateDocumentRequest): ResponseEntity<EvaluationResult> =
        ResponseEntity.ok(evaluated(evaluateDocumentRequest, draft = false))

    override fun evaluatePreIssuanceDraft(
        evaluateDocumentRequest: EvaluateDocumentRequest,
    ): ResponseEntity<EvaluationResult> = ResponseEntity.ok(evaluated(evaluateDocumentRequest, draft = true))

    /**
     * The dates of one evaluation (INV-Q-14414 A): [rateDate] is the date the rate, the category code and the mapping,
     * code-catalogue and scenario packs resolve at, [ownDate] the one the scope verdict resolves at. They differ only
     * for a credit note that states `precedingDocumentDate`. [extraTrace] and [straddleNote] are set for a credit note.
     */
    private data class Dating(
        val ownDate: TransactionDate,
        val rateDate: TransactionDate,
        val extraTrace: Map<String, Any>,
        val straddleNote: String?,
    )

    private fun evaluated(
        request: EvaluateDocumentRequest,
        draft: Boolean,
    ): EvaluationResult {
        val tenant = tenants.current()
        val ownDate = TransactionDate(request.transactionDate)
        val elected = electedOf(request)
        val input =
            LineInput(
                internalTaxCode = request.internalTaxCode,
                direction = request.direction?.let { Direction.valueOf(it.value) },
                elections = elected.first,
                electionsSource = elected.second,
                counterpartyApprovedSchemes = request.counterpartySchemeData?.approvedSchemes?.toSet(),
                writtenAgreement = agreementOf(request.lineArcsGstChargeAgreed),
                counterpartyWrittenAgreement = agreementOf(request.counterpartySchemeData?.arcsGstChargeAgreed),
            )
        return transactions.inTransaction {
            val classified = classificationOf(request, tenant)
            if (classified is Classified.Refused) {
                return@inTransaction refusedClassification(request, tenant, ownDate, classified)
            }
            val classifiedResult = classified as? Classified.Done
            // a credit note is exactly a request the classification yields "381" for (INV-Q-14414 A); for any other
            // request the two preceding fields are ignored
            if (classifiedResult?.typeCode == CREDIT_NOTE_TYPE_CODE) {
                creditNoteEvaluation(request, tenant, ownDate, input, classifiedResult, draft)
            } else {
                evaluatedAt(request, tenant, Dating(ownDate, ownDate, emptyMap(), null), input, classified, null)
            }
        }
    }

    /**
     * A credit note: `CreditNoteCoding` resolves the dates (INV-REQ-701, INV-REQ-702). On the draft operation a number
     * without a date is refused with 400 SRT-001 before anything is looked up (INV-Q-14421 B); on `evaluateDocument` it
     * is RUL-008 and nothing is coded (INV-Q-14404 A, INV-AC-1819). The controller makes no lookup.
     */
    private fun creditNoteEvaluation(
        request: EvaluateDocumentRequest,
        tenant: TenantId,
        ownDate: TransactionDate,
        input: LineInput,
        classified: Classified.Done,
        draft: Boolean,
    ): EvaluationResult {
        val number = request.precedingDocumentNumber?.takeUnless { it.isBlank() }
        val stated = request.precedingDocumentDate?.let { TransactionDate(it) }
        if (draft && number != null && stated == null) {
            throw ApiRefusal(
                ClosedErrorCode.CONTRACT_VIOLATION,
                "precedingDocumentDate is required when precedingDocumentNumber is given",
            )
        }
        val command =
            CodeCreditNoteCommand(tenant, ownDate, number, stated, listOf(DocumentLine(LINE_ID, ZERO, ZERO, input)))
        return when (val coded = creditNotes.code(command)) {
            is CreditNoteCodingResult.UnknownOriginal -> {
                unknownOriginal(request, tenant, ownDate, coded, classified)
            }

            is CreditNoteCodingResult.Coded -> {
                evaluatedAt(
                    request,
                    tenant,
                    datingOf(coded.trace),
                    input,
                    classified,
                    DocumentCodingResult.Coded(coded.document),
                )
            }

            is CreditNoteCodingResult.NotCoded -> {
                evaluatedAt(request, tenant, datingOf(coded.trace), input, classified, coded.result)
            }
        }
    }

    private fun datingOf(trace: RateDateTrace): Dating =
        Dating(
            trace.ownDate,
            trace.rateDate,
            listOfNotNull(
                "ownDate" to trace.ownDate.value.toString(),
                trace.precedingDate?.let { "precedingDate" to it.value.toString() },
                "rateDate" to trace.rateDate.value.toString(),
            ).toMap(),
            trace.justificationNote,
        )

    /** RUL-008: BLOCK, no `categoryCode`, no `rate`; the scope verdict is still decided at the own date. */
    private fun unknownOriginal(
        request: EvaluateDocumentRequest,
        tenant: TenantId,
        ownDate: TransactionDate,
        unknown: CreditNoteCodingResult.UnknownOriginal,
        classified: Classified.Done,
    ): EvaluationResult {
        val coding = LineCoding.Refused(unknown.code)
        val (outcome, answered) = scopeOutcomeOf(request, tenant, ownDate, coding)
        val trace =
            ScopeRequestFacts.traceWith(null, outcome).orEmpty() +
                (CLASSIFICATION to classificationTrace(classified.entry))
        return answer(
            outcome,
            answered,
            DocumentCodingResult.Refused(unknown.code, PackTypes.SCOPE, ownDate),
            listOf(classified.version),
            trace,
            classified,
            null,
        )
    }

    /**
     * The coding and the answer at [dating]: the pack-resolved parts at `rateDate`, the scope verdict at `ownDate`.
     * [coded] is the coding a credit note already has; null codes the line here.
     */
    private fun evaluatedAt(
        request: EvaluateDocumentRequest,
        tenant: TenantId,
        dating: Dating,
        input: LineInput,
        classified: Classified,
        coded: DocumentCodingResult?,
    ): EvaluationResult {
        val rateDate = dating.rateDate
        val result =
            coded
                ?: evaluate.code(
                    CodeDocumentCommand(tenant, rateDate, listOf(DocumentLine(LINE_ID, ZERO, ZERO, input))),
                )
        val coding = codingOf(result, tenant, rateDate, input)
        val (outcome, answered) = scopeOutcomeOf(request, tenant, dating.ownDate, coding)
        val trace = ScopeRequestFacts.traceWith(traceOf(result, tenant, rateDate, input), outcome)
        val classifiedResult = classified as? Classified.Done
        val withClassification =
            classifiedResult?.let { trace.orEmpty() + (CLASSIFICATION to classificationTrace(it.entry)) } ?: trace
        val withDates =
            if (dating.extraTrace.isEmpty()) {
                withClassification
            } else {
                withClassification.orEmpty() +
                    dating.extraTrace
            }
        val versions = versionsApplied(tenant, rateDate) + listOfNotNull(classifiedResult?.version)
        return answer(outcome, answered, result, versions, withDates, classified, dating.straddleNote)
    }

    /** What the classification of the request came to; [None] when the request carries no `documentFunction`. */
    private sealed interface Classified {
        data object None : Classified

        data class Done(
            val function: DocumentFunction,
            val typeCode: String?,
            val signNormalised: Boolean,
            val cancellation: Boolean,
            val entry: ClassificationTraceEntry,
            val version: PackVersionApplied,
        ) : Classified

        /** An asserted NOT_A_BILL whose complete key selects no row stays NOT_A_BILL (INV-Q-14802 A). */
        data object AssertedNotABill : Classified

        /** [scopeVersion] is the scope pack version in force when one was; the refusal never invents one. */
        data class Refused(
            val code: ErrorCode,
            val scopeVersion: PackVersionApplied?,
        ) : Classified
    }

    /**
     * Classifies the request by the scope pack classification table at the transaction date (never the preceding
     * document's, INV-Q-14414 A). A `documentFunction` without `documentKind` or `lineSign` selects no row (fail
     * closed, INV-Q-14800 A). A key with no row is RUL-012 and no scope pack in force is RUL-002 (INV-Q-14802 A).
     */
    @Suppress("ReturnCount") // the three guard answers read in order: no input, no key part, the table
    private fun classificationOf(
        request: EvaluateDocumentRequest,
        tenant: TenantId,
    ): Classified {
        val function = request.documentFunction ?: return Classified.None
        val date = TransactionDate(request.transactionDate)
        val kind = request.documentKind
        val sign = request.lineSign
        if (kind == null || sign == null) {
            val version = scopeVersionAt(tenant, date)
            return Classified.Refused(if (version == null) ErrorCode.RUL_002 else ErrorCode.RUL_012, version)
        }
        val input =
            ClassifyInput(
                DocumentFunction.valueOf(function.value),
                kind,
                listOf(ClassifiedLine.ofSign(LINE_ID, SignOfTotal.valueOf(sign.value))),
            )
        return when (val result = classify.classify(ClassifyCommand(tenant, date, input))) {
            is ClassifyResult.Refused -> Classified.Refused(result.code, null)
            is ClassifyResult.NoClassificationRow -> Classified.Refused(ErrorCode.RUL_012, scopeVersionAt(tenant, date))
            is ClassifyResult.Classified -> classifiedOf(result.classification, tenant, date)
        }
    }

    @Suppress("UnusedParameter") // tenant and date serve the fail-closed branch of a mixed-sign hold
    private fun classifiedOf(
        classification: Classification,
        tenant: TenantId,
        date: TransactionDate,
    ): Classified =
        when (classification) {
            is Classification.InvoiceData -> {
                done(
                    classification.documentFunction,
                    classification.documentTypeCode,
                    classification,
                    classification.trace,
                )
            }

            // an asserted NOT_A_BILL that no row contradicts stays NOT_A_BILL, never submitted (INV-Q-14802 A)
            is Classification.NotABill -> {
                classification.trace?.let { done(it.documentFunction, it.documentTypeCode, classification, it) }
                    ?: Classified.AssertedNotABill
            }

            is Classification.HeldMixedSign -> {
                // one `lineSign` is one sign, so a mix cannot arise here; it fails closed rather than being coded
                Classified.Refused(classification.code, scopeVersionAt(tenant, date))
            }
        }

    private fun done(
        function: DocumentFunction,
        typeCode: String?,
        classification: Classification,
        entry: ClassificationTraceEntry,
    ) = Classified.Done(
        function,
        typeCode,
        entry.signNormalised,
        (classification as? Classification.InvoiceData)?.cancellation ?: false,
        entry,
        PackVersionApplied(entry.applied.packType.value, entry.applied.version.value),
    )

    private fun scopeVersionAt(
        tenant: TenantId,
        date: TransactionDate,
    ): PackVersionApplied? =
        packs
            .versionAt(
                PackTypes.SCOPE,
                tenant,
                date,
            )?.let { PackVersionApplied(PackTypes.SCOPE.value, it.version.value) }

    private fun classificationTrace(entry: ClassificationTraceEntry): Map<String, Any> =
        mapOf(
            "ruleId" to DocumentClassifier.RULE_ID,
            "tableKey" to "${entry.upstreamFunction}/${entry.upstreamKind}/${entry.signOfTotal}",
            "packVersion" to entry.applied.version.value,
        )

    /** A document that is not classified is not coded: the refusal is the answer, built as any refused line's. */
    private fun refusedClassification(
        request: EvaluateDocumentRequest,
        tenant: TenantId,
        date: TransactionDate,
        refused: Classified.Refused,
    ): EvaluationResult {
        val coding = LineCoding.Refused(refused.code)
        val (outcome, answered) = scopeOutcomeOf(request, tenant, date, coding)
        val versions = versionsApplied(tenant, date) + listOfNotNull(refused.scopeVersion)
        val trace = ScopeRequestFacts.traceWith(null, outcome)
        return answer(
            outcome,
            answered,
            DocumentCodingResult.Refused(refused.code, PackTypes.SCOPE, date),
            versions,
            trace,
            null,
            null,
        )
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
        val facts =
            ScopeRequestFacts.factsOf(
                request,
                tenant,
                date,
                coding,
                profile.datesOf(tenant, request.entityRef, date),
                profile.exemptionOf(tenant, request.entityRef, date),
                voluntary.isOn(tenant, request.entityRef, date),
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

    /**
     * The decision trace of a line under a scheme tag (INV-REQ-2447 behavior 3): the handler, the pack entry and its
     * source class, and the counterparty data used. Null for any other line, and when no pack answered.
     */
    private fun traceOf(
        result: DocumentCodingResult,
        tenant: TenantId,
        date: TransactionDate,
        input: LineInput,
    ): Map<String, Any>? =
        if (result is DocumentCodingResult.Refused) {
            null
        } else {
            LineCoder(
                checkNotNull(packs.mappingAt(tenant, date)),
                checkNotNull(packs.catalogueAt(tenant, date)),
                checkNotNull(packs.scenarioAt(tenant, date)),
            ).codeWithTrace(input).second.takeIf { it.isNotEmpty() }
        }

    /**
     * The elected set and the property that supplied it (INV-Q-16862): `electedSchemes` when the request has it, even
     * when empty; else `election` as a set of one; else the empty set.
     */
    private fun electedOf(request: EvaluateDocumentRequest): Pair<Set<String>, String> {
        val array = request.electedSchemes
        val single = request.election
        return when {
            array != null -> array.toSet() to ELECTED_SCHEMES
            single != null -> setOf(single) to ELECTION
            else -> emptySet<String>() to NO_ELECTION
        }
    }

    private fun agreementOf(flag: Boolean?): String? = flag?.let { if (it) "AGREED" else "NOT_AGREED" }

    private fun versionsApplied(
        tenant: TenantId,
        date: TransactionDate,
    ): List<PackVersionApplied> =
        listOf(PackTypes.MAPPING, PackTypes.CODE_CATALOGUE, PackTypes.SCENARIO).mapNotNull { type ->
            packs.versionAt(type, tenant, date)?.let { PackVersionApplied(type.value, it.version.value) }
        }

    @Suppress("LongParameterList", "LongMethod") // the answer is assembled from the parts of one evaluation
    private fun answer(
        outcome: ScopeOutcome,
        coding: LineCoding,
        result: DocumentCodingResult,
        versions: List<PackVersionApplied>,
        trace: Map<String, Any>?,
        classified: Classified?,
        straddleNote: String?,
    ): EvaluationResult {
        val fields = fieldsOf(classified)
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
                    decisionTrace = trace,
                    straddleNote = straddleNote,
                    documentFunction = fields?.function,
                    documentTypeCode = fields?.typeCode,
                    signNormalised = fields?.signNormalised,
                    cancellation = fields?.cancellation?.takeIf { it },
                )
            }

            is LineCoding.Coded -> {
                val line = (result as DocumentCodingResult.Coded).document.lines.single()
                EvaluationResult(
                    scopeVerdict = scope,
                    categoryCode = ScopeRequestFacts.categoryCodeOf(outcome, coding.categoryCode),
                    rate = line.rate,
                    ibt022Statement = coding.ibt022Statement,
                    disregardedSupplyWarning = warning,
                    decisionTrace = trace,
                    packVersionsApplied = versions,
                    straddleNote = straddleNote,
                    documentFunction = fields?.function,
                    documentTypeCode = fields?.typeCode,
                    signNormalised = fields?.signNormalised,
                    cancellation = fields?.cancellation?.takeIf { it },
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

    /** The four classification fields of the answer; null when none is answered. */
    private data class Fields(
        val function: EvaluationResult.DocumentFunction,
        val typeCode: String?,
        val signNormalised: Boolean?,
        val cancellation: Boolean?,
    )

    private fun fieldsOf(classified: Classified?): Fields? =
        when (classified) {
            is Classified.Done -> {
                Fields(
                    EvaluationResult.DocumentFunction.valueOf(classified.function.name),
                    classified.typeCode,
                    classified.signNormalised,
                    classified.cancellation,
                )
            }

            Classified.AssertedNotABill -> {
                Fields(EvaluationResult.DocumentFunction.NOT_A_BILL, null, null, null)
            }

            else -> {
                null
            }
        }

    private companion object {
        const val LINE_ID = "1"
        const val CLASSIFICATION = "classification"
        const val CREDIT_NOTE_TYPE_CODE = "381"
        val ZERO: BigDecimal = BigDecimal.ZERO
    }
}
