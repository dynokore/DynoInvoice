package com.dynokore.platform.rulesevaluation

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.platform.rulesevaluation.coding.MappingEntry
import com.dynokore.platform.rulesevaluation.coding.TenantMapping
import com.dynokore.platform.rulesevaluation.scope.ExemptElection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The composed [evaluate] over invented packs. The classification cases are the cases of svc-rules
 * `ClassificationBoundaryTest` that exercise the moved logic, run through the pure function: the same rows, keys and
 * expected type codes.
 */
class EvaluateTest {
    private data class Row(
        val upstream: String,
        val kind: String,
        val sign: String,
        val function: String,
        val code: String?,
        val normalised: Boolean,
        val cancellation: Boolean = false,
    ) {
        fun json() =
            """{"upstreamFunction":"$upstream","upstreamKind":"$kind","signOfTotal":"$sign",""" +
                """"documentFunction":"$function",""" +
                code?.let { """"documentTypeCode":"$it",""" }.orEmpty() +
                """"signNormalised":$normalised,"cancellation":$cancellation,""" +
                """"sourceClass":"PRIMARY","sourceReference":"t"}"""
    }

    private val negativeInvoice = Row("BILL_FOR_PAYMENT", "INVOICE", "NEGATIVE", "ADJUSTMENT", "381", true)
    private val plainInvoice = Row("BILL_FOR_PAYMENT", "INVOICE", "POSITIVE", "BILL_FOR_PAYMENT", "380", false)
    private val debitNote = Row("ADJUSTMENT", "DEBITNOTE", "POSITIVE", "ADJUSTMENT", "380", false)
    private val cancellation = Row("ADJUSTMENT", "CANCEL", "NEGATIVE", "ADJUSTMENT", "381", true, cancellation = true)
    private val notABill = Row("NOT_A_BILL", "STATEMENT", "POSITIVE", "NOT_A_BILL", null, false)
    private val rows = listOf(negativeInvoice, plainInvoice, debitNote, cancellation, notABill)

    private val catalogue =
        """{"complete":true,"codes":[{"code":"SR","group":"SUPPLY_STANDARD_RATED","side":"SUPPLY","annexERate":"9",""" +
            """"sourceClass":"PRIMARY","sourceReference":"invented"},""" +
            """{"code":"TX","group":"PURCHASE_STANDARD_RATED","side":"PURCHASE","annexERate":"9",""" +
            """"sourceClass":"PRIMARY","sourceReference":"invented"},""" +
            """{"code":"OS","group":"SUPPLY_ZERO_RATED_EXEMPT_OUT_OF_SCOPE","side":"SUPPLY","annexERate":"0",""" +
            """"sourceClass":"PRIMARY","sourceReference":"invented"}]}"""
    private val scenario =
        """{"selfBillingAssignment":{"customerCumIssuer":{"direction":"PURCHASE","submissionType":"TYPE_3",""" +
            """"sourceClass":"INFERRED","sourceReference":"s"},"supplierCumRecipient":{"direction":"SALE",""" +
            """"submissionType":"TYPE_2","sourceClass":"INFERRED","sourceReference":"s"}},""" +
            """"schemeTagSides":[{"tag":"A3PL","side":"SUPPLY","handling":"HANDLED","sourceClass":"PRIMARY",""" +
            """"sourceReference":"s"}],"treatments":[{"tag":"A3PL","side":"SUPPLY","categoryCode":"ZR",""" +
            """"sourceClass":"PRIMARY","sourceReference":"s","rateBasis":"FIXED","rate":"0"}],""" +
            """"approvableSchemes":[{"scheme":"MES","sourceClass":"PRIMARY","sourceReference":"s"}]}"""
    private val scope =
        """{"classificationTable":[${rows.joinToString(",") { it.json() }}],"lineScopeRules":[""" +
            """{"categoryCode":"SR","lineVerdict":"IN_SCOPE","sourceClass":"PRIMARY",""" +
            """"sourceReference":"invented"},""" +
            """{"categoryCode":"TX","lineVerdict":"IN_SCOPE","sourceClass":"PRIMARY","sourceReference":"invented"},""" +
            """{"categoryCode":"OS","lineVerdict":"OUT_OF_SCOPE","sourceClass":"PRIMARY",""" +
            """"sourceReference":"invented"}],""" +
            """"intraGroupRule":{"treatment":"PASS_THROUGH","sourceClass":"INFERRED","sourceReference":"t"}}"""

    private val packs =
        mapOf(
            EvaluationPackTypes.CODE_CATALOGUE to catalogue,
            EvaluationPackTypes.SCENARIO to scenario,
            EvaluationPackTypes.SCOPE to scope,
        )
    private val mapping get() =
        TenantMapping(
            listOf(MappingEntry("STD", "SR", null), MappingEntry("PUR", "TX", null), MappingEntry("OSX", "OS", null)),
        )
    private val profile =
        EvaluationProfile(
            obligationStartDate = LocalDate.of(2027, 1, 1),
            registrationEndDate = null,
            voluntaryOverSubmission = false,
            partiallyExempt = false,
            election = null,
        )

    private fun request(
        row: Row? = null,
        kind: String? = row?.kind,
        function: String? = row?.upstream,
        sign: String? = row?.sign,
        code: String = "STD",
        date: LocalDate = LocalDate.of(2027, 3, 1),
        direction: String? = null,
        claimed: Boolean? = null,
    ) = EvaluateRequest(
        direction = direction,
        inputTaxClaimedNonZero = claimed,
        transactionDate = date,
        internalTaxCode = code,
        documentFunction = function,
        documentKind = kind,
        lineSign = sign,
    )

    private fun run(request: EvaluateRequest) = evaluate(request, packs, mapping, profile)

    private fun typeCodeOf(row: Row) = evaluate(request(row), packs, mapping, profile).documentTypeCode

    @Test
    fun `INV-AC-19453 the three fields equal the values the moved cases expect, per table row`() {
        typeCodeOf(negativeInvoice) shouldBe "381"
        typeCodeOf(plainInvoice) shouldBe "380"
        typeCodeOf(debitNote) shouldBe "380"
        typeCodeOf(cancellation) shouldBe "381"
        typeCodeOf(notABill) shouldBe null
        rows.forEach { evaluate(request(it), packs, mapping, profile).categoryCode shouldBe "SR" }
        rows.forEach { evaluate(request(it), packs, mapping, profile).scopeVerdict shouldBe "IN_SCOPE" }
    }

    @Test
    fun `INV-AC-19453 an asserted NOT_A_BILL with no row has no type code, and no function is unclassified`() {
        val asserted = run(request(negativeInvoice, function = "NOT_A_BILL", sign = "POSITIVE"))
        asserted.documentTypeCode shouldBe null
        asserted.categoryCode shouldBe "SR"
        evaluate(request(), packs, mapping, profile).documentTypeCode shouldBe null
        evaluate(request(), packs, mapping, profile).categoryCode shouldBe "SR"
    }

    @Test
    fun `INV-AC-19453 the same inputs give equal results twice and in a fresh instance`() {
        val first = evaluate(request(negativeInvoice), packs, mapping, profile)
        evaluate(request(negativeInvoice), packs, mapping, profile) shouldBe first
        val freshMapping = TenantMapping(listOf(MappingEntry("STD", "SR", null)))
        val freshPacks = packs.mapValues { String(it.value.toByteArray()) }
        evaluate(request(negativeInvoice), freshPacks, freshMapping, profile) shouldBe
            first
    }

    @Test
    fun `INV-AC-19453 an absent pack throws PackAbsent naming it, for each pack type`() {
        listOf(EvaluationPackTypes.CODE_CATALOGUE, EvaluationPackTypes.SCENARIO, EvaluationPackTypes.SCOPE).forEach {
            val thrown = shouldThrow<PackAbsent> { evaluate(request(negativeInvoice), packs - it, mapping, profile) }
            thrown.packType shouldBe it
            thrown.code shouldBe ErrorCode.RUL_002
        }
    }

    @Test
    fun `INV-AC-19453 an unmapped code throws UnmappedCode and applies no default`() {
        val thrown = shouldThrow<UnmappedCode> { run(request(negativeInvoice, code = "NO-SUCH")) }
        thrown.internalTaxCode shouldBe "NO-SUCH"
        thrown.code shouldBe ErrorCode.RUL_001
        shouldThrow<UnmappedCode> { run(request(code = "std")) } // exact equality, no folding
    }

    @Test
    fun `INV-AC-19453 a classification key with no row throws NoClassificationRow, also for look-alikes`() {
        listOf(
            request(negativeInvoice, kind = "INVOICE "),
            request(negativeInvoice, kind = "INVOICE2"),
            request(negativeInvoice, kind = "invoice"),
            request(negativeInvoice, function = "ADJUSTMENT"),
            request(negativeInvoice, sign = "ZERO"),
            request(negativeInvoice, kind = null),
            request(negativeInvoice, sign = null),
            request(notABill, kind = null),
            request(notABill, sign = null),
        ).forEach {
            shouldThrow<NoClassificationRow> { evaluate(it, packs, mapping, profile) }
        }
    }

    private fun verdictOf(
        profile: EvaluationProfile,
        request: EvaluateRequest = request(),
    ) = evaluate(request, packs, mapping, profile).scopeVerdict

    private val purchase get() = request(code = "PUR", direction = "PURCHASE", claimed = false)
    private val elected = ExemptElection(setOf("PUR"), "recorded")

    @Test
    fun `INV-AC-19453 a date before obligationStartDate and a null obligationStartDate give PRE_OBLIGATION`() {
        verdictOf(profile) shouldBe "IN_SCOPE"
        verdictOf(profile, request(date = LocalDate.of(2026, 12, 31))) shouldBe "PRE_OBLIGATION"
        verdictOf(profile.copy(obligationStartDate = null)) shouldBe "PRE_OBLIGATION"
        verdictOf(profile, request(date = LocalDate.of(2027, 1, 1))) shouldBe "IN_SCOPE" // the start date is inside
    }

    @Test
    fun `INV-AC-19453 a date after registrationEndDate gives NOT_SUBMITTABLE`() {
        val ended = profile.copy(registrationEndDate = LocalDate.of(2027, 2, 28))
        verdictOf(ended) shouldBe "NOT_SUBMITTABLE"
        verdictOf(ended, request(date = LocalDate.of(2027, 2, 28))) shouldBe "IN_SCOPE" // the end date is inside
        verdictOf(profile.copy(registrationEndDate = LocalDate.of(2027, 3, 1))) shouldBe "IN_SCOPE"
    }

    @Test
    fun `INV-AC-19453 voluntaryOverSubmission true and false give the two verdicts`() {
        val os = request(code = "OSX")
        verdictOf(profile.copy(voluntaryOverSubmission = false), os) shouldBe "OUT_OF_SCOPE"
        verdictOf(profile.copy(voluntaryOverSubmission = true), os) shouldBe "SUBMITTABLE"
        // the purchase with no input tax claimed: OUT_OF_SCOPE, or SUBMITTABLE under voluntary over-submission
        verdictOf(profile, purchase) shouldBe "OUT_OF_SCOPE"
        verdictOf(profile.copy(voluntaryOverSubmission = true), purchase) shouldBe "SUBMITTABLE"
    }

    @Test
    fun `INV-AC-19453 partiallyExempt changes the verdict of a purchase with no input tax claimed`() {
        verdictOf(profile.copy(partiallyExempt = false), purchase) shouldBe "OUT_OF_SCOPE"
        verdictOf(profile.copy(partiallyExempt = true), purchase) shouldBe "IN_SCOPE"
    }

    @Test
    fun `INV-AC-19453 election excludes the purchases it names for a partially exempt entity only`() {
        val partial = profile.copy(partiallyExempt = true)
        verdictOf(partial.copy(election = null), purchase) shouldBe "IN_SCOPE"
        verdictOf(partial.copy(election = elected), purchase) shouldBe "OUT_OF_SCOPE"
        verdictOf(partial.copy(election = ExemptElection(setOf("PUR2"), "recorded")), purchase) shouldBe "IN_SCOPE"
        val claimed = request(code = "PUR", direction = "PURCHASE", claimed = true)
        verdictOf(profile.copy(election = elected), claimed) shouldBe "IN_SCOPE" // not partial: election not read
    }

    @Test
    fun `INV-AC-19453 two profiles equal in all members but one give different results in that member's case`() {
        val base = profile.copy(partiallyExempt = true, election = elected)
        verdictOf(base, purchase) shouldBe "OUT_OF_SCOPE"
        verdictOf(base.copy(obligationStartDate = LocalDate.of(2027, 3, 2)), purchase) shouldBe "PRE_OBLIGATION"
        verdictOf(base.copy(registrationEndDate = LocalDate.of(2027, 2, 1)), purchase) shouldBe "NOT_SUBMITTABLE"
        verdictOf(base.copy(partiallyExempt = false), purchase) shouldBe "OUT_OF_SCOPE" // unclaimed, not partial
        verdictOf(base.copy(partiallyExempt = false, voluntaryOverSubmission = true), purchase) shouldBe "SUBMITTABLE"
        verdictOf(base.copy(election = null), purchase) shouldBe "IN_SCOPE"
    }

    @Test
    fun `INV-AC-19453 a key with no row throws NoClassificationRow with the code RUL-012 of gst-catalogue`() {
        val thrown = shouldThrow<NoClassificationRow> { run(request(negativeInvoice, kind = "NO-KIND")) }
        thrown.code shouldBe ErrorCode.RUL_012
        thrown.code.code shouldBe "RUL-012"
    }

    @Test
    fun `INV-AC-19453 the request is read by the contract's property names`() {
        EvaluateRequest::class
            .java.declaredFields
            .map { it.name }
            .toSet() shouldBe
            setOf(
                "transactionDate",
                "internalTaxCode",
                "precedingDocumentDate",
                "election",
                "electedSchemes",
                "counterpartySchemeData",
                "lineArcsGstChargeAgreed",
                "documentFunction",
                "documentKind",
                "lineSign",
                "apportionmentMode",
                "mixedUse",
                "direction",
                "sameGroup",
                "inputTaxClaimedNonZero",
            )
    }
}
