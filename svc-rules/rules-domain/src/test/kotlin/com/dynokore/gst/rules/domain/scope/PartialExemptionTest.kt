package com.dynokore.gst.rules.domain.scope

import com.dynokore.gst.rules.domain.coding.LineCoding
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The partial-exemption and apportionment treatment of the scope rules (INV-REQ-382, 367, 369, 370). */
class PartialExemptionTest {
    private val day = TransactionDate(LocalDate.of(2026, 6, 1))

    private val rules =
        ScopeRules(
            ScopeRulesData(
                lineVerdicts =
                    mapOf(
                        "TX-RE" to ScopeVerdict.IN_SCOPE,
                        "TX-ESS" to ScopeVerdict.IN_SCOPE,
                        "TX-N33" to ScopeVerdict.IN_SCOPE,
                        "TX" to ScopeVerdict.IN_SCOPE,
                        "BL" to ScopeVerdict.EXCLUDED_TXN,
                    ),
                intraGroupTreatment = "PASS_THROUGH",
                purchaseStandardRatedCodes = setOf("TX-RE", "TX-ESS", "TX-N33", "TX"),
            ),
        )

    private val election = ExemptElection(setOf("TX-N33"), "significant non-Regulation 33 exempt supplies")

    private fun outcome(
        code: String,
        exemption: PartialExemptionFacts = PartialExemptionFacts(),
        claimed: Boolean? = true,
        internal: String? = null,
        direction: ScopeDirection = ScopeDirection.PURCHASE,
    ) = rules.outcomeOf(
        ScopeFacts(
            null,
            day,
            LineCoding.Coded(code),
            direction,
            false,
            claimed,
            false,
            TransactionDate(LocalDate.of(2026, 1, 1)),
            null,
            internal,
            exemption,
        ),
    )

    private val partial = PartialExemptionFacts(partiallyExempt = true)

    private fun rulesOf(o: ScopeOutcome) = o.trace.map { it.rule }

    @Test
    fun `INV-AC-398 a partially exempt standard-rated purchase with input tax not claimed is submitted`() {
        val o = outcome("TX-RE", partial, claimed = false)
        o.verdict shouldBe ScopeVerdict.IN_SCOPE
        o.categoryCode shouldBe null
        rulesOf(o) shouldBe listOf(PartialExemption.RULE_PARTIAL_EXEMPTION)
    }

    @Test
    fun `INV-AC-1656 an entity not recorded as partially exempt gets the general rule and no trace`() {
        val o = outcome("TX-RE", PartialExemptionFacts(partiallyExempt = false), claimed = false)
        o.verdict shouldBe ScopeVerdict.OUT_OF_SCOPE
        rulesOf(o) shouldBe emptyList()
        outcome("TX-RE", PartialExemptionFacts(), claimed = false).verdict shouldBe ScopeVerdict.OUT_OF_SCOPE
        // a recorded election without the status does not exclude either
        val withElection = PartialExemptionFacts(false, election)
        outcome("TX-N33", withElection).verdict shouldBe ScopeVerdict.IN_SCOPE
    }

    @Test
    fun `INV-AC-1643 a partially exempt disallowed expense mapped to BL is excluded without an election`() {
        val o = outcome("BL", partial)
        o.verdict shouldBe ScopeVerdict.EXCLUDED_TXN
        rulesOf(o) shouldBe listOf(PartialExemption.RULE_NON_REPORTABLE)
    }

    @Test
    fun `INV-AC-2782 a non-partially-exempt purchase mapped to BL is excluded and the trace names BL`() {
        val o = outcome("BL")
        o.verdict shouldBe ScopeVerdict.EXCLUDED_TXN
        o.trace shouldBe listOf(TraceEntry(PartialExemption.RULE_NON_REPORTABLE, mapOf("categoryCode" to "BL")))
        // data without a BL row still excludes it; a sale coded BL is not a disallowed expense
        val bare = ScopeRules(ScopeRulesData(mapOf("SR" to ScopeVerdict.IN_SCOPE), "PASS_THROUGH", emptySet()))
        bare.verdictOf(facts("BL")) shouldBe ScopeVerdict.OUT_OF_SCOPE
        outcome("BL", direction = ScopeDirection.SALE).verdict shouldBe ScopeVerdict.EXCLUDED_TXN
        outcome("bl").verdict shouldBe ScopeVerdict.NOT_SUBMITTABLE
        outcome("BLX").verdict shouldBe ScopeVerdict.NOT_SUBMITTABLE
    }

    private fun facts(code: String) =
        ScopeFacts(
            null,
            day,
            LineCoding.Coded(code),
            ScopeDirection.PURCHASE,
            obligationStartDate = TransactionDate(LocalDate.of(2026, 1, 1)),
        )

    @Test
    fun `INV-AC-1642 a partially exempt purchase keeps the code the business assigned at processing`() {
        val o = outcome("TX-ESS", partial)
        o.verdict shouldBe ScopeVerdict.IN_SCOPE
        o.categoryCode shouldBe null
        listOf("TX-ESS", "TX-N33", "TX-RE").forEach { outcome(it, partial).categoryCode shouldBe null }
    }

    @Test
    fun `INV-AC-392 a TX-RE purchase is decided the same before and after de minimis, nothing is recoded`() {
        val first = outcome("TX-RE", partial, claimed = false)
        val again = outcome("TX-RE", partial, claimed = true)
        first.verdict shouldBe again.verdict
        first.categoryCode shouldBe null
        again.categoryCode shouldBe null
    }

    @Test
    fun `INV-AC-399 with the recorded election TX-N33 legal fees are excluded and the trace names basis`() {
        val o = outcome("TX-N33", PartialExemptionFacts(true, election), internal = "LEGAL")
        o.verdict shouldBe ScopeVerdict.OUT_OF_SCOPE
        o.trace.last() shouldBe
            TraceEntry(
                PartialExemption.RULE_EXEMPT_ELECTION,
                mapOf("codes" to "TX-N33", "basis" to "significant non-Regulation 33 exempt supplies"),
            )
        // the election may also name the internal code
        val byInternal = PartialExemptionFacts(true, ExemptElection(setOf("LEGAL"), "b"))
        outcome("TX-N33", byInternal, internal = "LEGAL").verdict shouldBe ScopeVerdict.OUT_OF_SCOPE
    }

    @Test
    fun `INV-AC-1645 without the election a TX-N33 purchase is submitted and look-alike codes are not excluded`() {
        outcome("TX-N33", partial, internal = "LEGAL").verdict shouldBe ScopeVerdict.IN_SCOPE
        val facts = PartialExemptionFacts(true, election)
        outcome("TX-N3", facts).verdict shouldBe ScopeVerdict.NOT_SUBMITTABLE
        listOf("TX-ESS", "TX-RE").forEach { outcome(it, facts).verdict shouldBe ScopeVerdict.IN_SCOPE }
        outcome("TX-N33", facts, internal = "tx-n33").verdict shouldBe ScopeVerdict.OUT_OF_SCOPE
        outcome("TX-RE", facts, internal = "tx-n33").verdict shouldBe ScopeVerdict.IN_SCOPE
        outcome("TX-RE", facts, internal = "TX-N33 ").verdict shouldBe ScopeVerdict.IN_SCOPE
    }

    @Test
    fun `INV-AC-393 claimable-portion mode gives a mixed-use purchase the code TX for the portion`() {
        val mode = ApportionmentMode.CLAIMABLE_PORTION
        val o = outcome("TX-RE", PartialExemptionFacts(false, null, mode, mode))
        o.categoryCode shouldBe "TX"
        o.verdict shouldBe ScopeVerdict.IN_SCOPE
        rulesOf(o) shouldBe listOf(PartialExemption.RULE_APPORTIONMENT)
        // a line that is not mixed-use, a sale and an entity with no election are not apportioned
        outcome("TX-RE", PartialExemptionFacts(false, null, mode, null)).categoryCode shouldBe null
        outcome("TX-RE", PartialExemptionFacts(false, null, null, mode)).categoryCode shouldBe null
        outcome("TX-RE", PartialExemptionFacts(false, null, mode, mode), direction = ScopeDirection.SALE)
            .categoryCode shouldBe null
    }

    @Test
    fun `INV-AC-1646 entire-invoice mode keeps the mapped code of a mixed-use purchase`() {
        val entire = ApportionmentMode.ENTIRE_INVOICE
        val o = outcome("TX-RE", PartialExemptionFacts(false, null, entire, entire))
        o.categoryCode shouldBe null
        o.verdict shouldBe ScopeVerdict.IN_SCOPE
        rulesOf(o) shouldBe emptyList()
        // a contradicting indicator is not apportioned here
        val clash = PartialExemptionFacts(false, null, entire, ApportionmentMode.CLAIMABLE_PORTION)
        outcome("TX-RE", clash).categoryCode shouldBe null
    }

    @Test
    fun `an election without a basis or without codes cannot be built`() {
        listOf(" ", "").forEach { b ->
            runCatching { ExemptElection(setOf("TX-N33"), b) }.isFailure shouldBe true
        }
        runCatching { ExemptElection(emptySet(), "b") }.isFailure shouldBe true
    }
}
