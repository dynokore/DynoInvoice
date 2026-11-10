package com.dynokore.platform.rulesevaluation

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.platform.rulesevaluation.classification.Classification
import com.dynokore.platform.rulesevaluation.classification.ClassificationTable
import com.dynokore.platform.rulesevaluation.classification.ClassifiedLine
import com.dynokore.platform.rulesevaluation.classification.ClassifyInput
import com.dynokore.platform.rulesevaluation.classification.DocumentClassifier
import com.dynokore.platform.rulesevaluation.classification.DocumentFunction
import com.dynokore.platform.rulesevaluation.classification.SignOfTotal
import com.dynokore.platform.rulesevaluation.coding.Direction
import com.dynokore.platform.rulesevaluation.coding.ELECTED_SCHEMES
import com.dynokore.platform.rulesevaluation.coding.ELECTION
import com.dynokore.platform.rulesevaluation.coding.LineCoder
import com.dynokore.platform.rulesevaluation.coding.LineCoding
import com.dynokore.platform.rulesevaluation.coding.LineInput
import com.dynokore.platform.rulesevaluation.coding.NO_ELECTION
import com.dynokore.platform.rulesevaluation.coding.TenantMapping
import com.dynokore.platform.rulesevaluation.packs.CodeCataloguePackContents
import com.dynokore.platform.rulesevaluation.packs.ScenarioPackContents
import com.dynokore.platform.rulesevaluation.packs.ScopePackContents
import com.dynokore.platform.rulesevaluation.resolution.TransactionDate
import com.dynokore.platform.rulesevaluation.scope.ApportionmentMode
import com.dynokore.platform.rulesevaluation.scope.ExemptElection
import com.dynokore.platform.rulesevaluation.scope.PartialExemptionFacts
import com.dynokore.platform.rulesevaluation.scope.ScopeDirection
import com.dynokore.platform.rulesevaluation.scope.ScopeEvaluation
import com.dynokore.platform.rulesevaluation.scope.ScopeFacts
import com.dynokore.platform.rulesevaluation.scope.ScopeRules
import com.dynokore.platform.rulesevaluation.scope.ScopeRulesData
import java.time.LocalDate

/** The pack types [evaluate] reads from its `packs` argument (the MAPPING pack arrives as a `TenantMapping`). */
object EvaluationPackTypes {
    const val CODE_CATALOGUE = "CODE_CATALOGUE"
    const val SCENARIO = "SCENARIO"
    const val SCOPE = "SCOPE"
}

/** The scheme data of the counterparty, as the request member `counterpartySchemeData` carries it. */
data class CounterpartySchemeData(
    val approvedSchemes: List<String>? = null,
    val arcsGstChargeAgreed: Boolean? = null,
)

/**
 * The members of `EvaluateDocumentRequest` of platform-contracts that the evaluation reads, under the same names and
 * with the enums as their contract text (`direction`, `documentFunction`, `lineSign`, `apportionmentMode`,
 * `mixedUse`). Members the evaluation does not read (`entityRef`, `precedingDocumentNumber`) are not carried.
 */
data class EvaluateRequest(
    val transactionDate: LocalDate,
    val internalTaxCode: String,
    val precedingDocumentDate: LocalDate? = null,
    val election: String? = null,
    val electedSchemes: List<String>? = null,
    val counterpartySchemeData: CounterpartySchemeData? = null,
    val lineArcsGstChargeAgreed: Boolean? = null,
    val documentFunction: String? = null,
    val documentKind: String? = null,
    val lineSign: String? = null,
    val apportionmentMode: String? = null,
    val mixedUse: String? = null,
    val direction: String? = null,
    val sameGroup: Boolean? = null,
    val inputTaxClaimedNonZero: Boolean? = null,
)

/**
 * The entity profile facts the scope rules decide on (INV-REQ-775: an explicit input, never read from elsewhere).
 * [obligationStartDate] null is the absent case that scope rule step 1 defines as `PRE_OBLIGATION`; the other
 * nullable members mean "not recorded". There is no default profile: [evaluate] takes one every call.
 */
data class EvaluationProfile(
    val obligationStartDate: LocalDate?,
    val registrationEndDate: LocalDate?,
    val voluntaryOverSubmission: Boolean,
    val partiallyExempt: Boolean,
    val election: ExemptElection?,
)

/** A refusal of [evaluate]: the answer is this exception, never a result with absent or defaulted fields. */
sealed class EvaluationRefusal(
    message: String,
) : RuntimeException(message)

/** A pack the evaluation needs is not among the `packs` given (RUL-002). */
class PackAbsent(
    val packType: String,
) : EvaluationRefusal("no $packType pack contents were given") {
    val code: ErrorCode get() = ErrorCode.RUL_002
}

/** The internal tax code has no mapping entry (RUL-001); no default code is applied. */
class UnmappedCode(
    val internalTaxCode: String,
) : EvaluationRefusal("internal tax code '$internalTaxCode' has no mapping entry") {
    val code: ErrorCode get() = ErrorCode.RUL_001
}

/**
 * The classification key selects no row (RUL-012 of gst-catalogue). [documentKind] or [lineSign] is null when the
 * request has a `documentFunction` without that key part, which
 * selects no row either (fail closed, INV-Q-14800 A).
 */
class NoClassificationRow(
    val documentFunction: String,
    val documentKind: String?,
    val lineSign: String?,
) : EvaluationRefusal("the key $documentFunction/$documentKind/$lineSign selects no classification row") {
    val code: ErrorCode get() = ErrorCode.RUL_012
}

/** The line coding was refused for another reason than a missing mapping entry; [code] is the coder's refusal. */
class LineRefused(
    val code: ErrorCode,
) : EvaluationRefusal("the line was refused with ${code.code}")

/** The classification held the document (mixed-sign lines); one `lineSign` cannot cause it, so it fails closed. */
class ClassificationHeld(
    val code: ErrorCode,
) : EvaluationRefusal("the classification held the document with ${code.code}")

/**
 * The composed pure evaluation of one line (INV-REQ-775): request to line input, coding through [mapping] and the
 * catalogue and scenario packs, the scope verdict through the SCOPE pack, and the classification through its table.
 * Reads no clock, store or network; the same inputs give an equal result. [packs] holds the contents JSON per pack type
 * ([EvaluationPackTypes]); [profile] carries the entity facts of the scope rules. A refusal is a thrown
 * [EvaluationRefusal]; no field of the result is defaulted.
 */
fun evaluate(
    request: EvaluateRequest,
    packs: Map<String, String>,
    mapping: TenantMapping,
    profile: EvaluationProfile,
): EvaluationResult = Evaluator(request, packs, mapping, profile).evaluate()

private class Evaluator(
    private val request: EvaluateRequest,
    private val packs: Map<String, String>,
    private val mapping: TenantMapping,
    private val profile: EvaluationProfile,
) {
    private fun contents(packType: String): ByteArray =
        (packs[packType] ?: throw PackAbsent(packType)).toByteArray(Charsets.UTF_8)

    fun evaluate(): EvaluationResult {
        val catalogue = CodeCataloguePackContents.decode(contents(EvaluationPackTypes.CODE_CATALOGUE))
        val scenario = ScenarioPackContents.decode(contents(EvaluationPackTypes.SCENARIO))
        val scope = contents(EvaluationPackTypes.SCOPE)
        // the classification is decided first: a document that is not classified is not coded (the controller's order)
        val typeCode = classify(scope)
        if (mapping.entryOf(request.internalTaxCode) == null) throw UnmappedCode(request.internalTaxCode)
        val coding = LineCoder(mapping, catalogue, scenario).code(lineInput())
        val coded =
            when (coding) {
                is LineCoding.Coded -> coding
                is LineCoding.Refused -> throw LineRefused(coding.code)
            }
        val data = ScopePackContents.decodeScopeData(scope)
        val purchaseRated =
            catalogue.entries
                .filter { it.group == PURCHASE_STANDARD_RATED }
                .map { it.code }
                .toSet()
        val rules = ScopeRules(ScopeRulesData(data.lineVerdicts, data.intraGroupTreatment, purchaseRated))
        val facts = scopeFacts(coding)
        val outcome = rules.outcomeOf(facts)
        return EvaluationResult(
            categoryCode = outcome.categoryCode ?: coded.categoryCode,
            scopeVerdict = ScopeEvaluation(rules).scopeVerdictOf(facts),
            documentTypeCode = typeCode,
        )
    }

    /** The type code of the table row; null for no function, a NOT_A_BILL row or an asserted NOT_A_BILL. */
    private fun classify(scope: ByteArray): String? {
        val function = request.documentFunction ?: return null
        val classification = classificationOf(function, scope)
        return when (classification) {
            is Classification.InvoiceData -> classification.documentTypeCode
            is Classification.NotABill -> classification.trace?.documentTypeCode
            is Classification.HeldMixedSign -> throw ClassificationHeld(classification.code)
        }
    }

    private fun classificationOf(
        function: String,
        scope: ByteArray,
    ): Classification {
        val kind = request.documentKind
        val sign = request.lineSign
        val noRow = NoClassificationRow(function, kind, sign)
        if (kind == null || sign == null) throw noRow
        val table = ClassificationTable(ScopePackContents.decodeClassificationTable(scope))
        val line = ClassifiedLine.ofSign(LINE_ID, SignOfTotal.valueOf(sign))
        return DocumentClassifier.classify(ClassifyInput(DocumentFunction.valueOf(function), kind, listOf(line)), table)
            ?: throw noRow
    }

    private fun lineInput(): LineInput {
        val elected = electedOf()
        return LineInput(
            internalTaxCode = request.internalTaxCode,
            direction = request.direction?.let { Direction.valueOf(it) },
            elections = elected.first,
            electionsSource = elected.second,
            counterpartyApprovedSchemes = request.counterpartySchemeData?.approvedSchemes?.toSet(),
            writtenAgreement = agreementOf(request.lineArcsGstChargeAgreed),
            counterpartyWrittenAgreement = agreementOf(request.counterpartySchemeData?.arcsGstChargeAgreed),
        )
    }

    private fun electedOf(): Pair<Set<String>, String> {
        val array = request.electedSchemes
        val single = request.election
        return when {
            array != null -> array.toSet() to ELECTED_SCHEMES
            single != null -> setOf(single) to ELECTION
            else -> emptySet<String>() to NO_ELECTION
        }
    }

    private fun agreementOf(flag: Boolean?): String? = flag?.let { if (it) "AGREED" else "NOT_AGREED" }

    private fun scopeFacts(coding: LineCoding): ScopeFacts =
        ScopeFacts(
            tenant = null,
            date = TransactionDate(request.precedingDocumentDate ?: request.transactionDate),
            line = coding,
            direction = request.direction?.let { ScopeDirection.valueOf(it) } ?: ScopeDirection.SALE,
            sameGroup = request.sameGroup ?: false,
            inputTaxClaimedNonZero = request.inputTaxClaimedNonZero,
            internalTaxCode = request.internalTaxCode,
            voluntaryOverSubmission = profile.voluntaryOverSubmission,
            obligationStartDate = profile.obligationStartDate?.let { TransactionDate(it) },
            registrationEndDate = profile.registrationEndDate?.let { TransactionDate(it) },
            exemption =
                PartialExemptionFacts(
                    partiallyExempt = profile.partiallyExempt,
                    election = profile.election,
                    apportionmentMode = request.apportionmentMode?.let { ApportionmentMode.valueOf(it) },
                    mixedUse = request.mixedUse?.let { ApportionmentMode.valueOf(it) },
                ),
        )

    private companion object {
        const val LINE_ID = "1"
        const val PURCHASE_STANDARD_RATED = "PURCHASE_STANDARD_RATED"
    }
}
