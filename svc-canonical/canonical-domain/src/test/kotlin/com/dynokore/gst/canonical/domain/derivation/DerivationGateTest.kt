package com.dynokore.gst.canonical.domain.derivation

import com.dynokore.gst.canonical.domain.derivation.DerivationStep.DOCUMENT_FUNCTION
import com.dynokore.gst.canonical.domain.derivation.DerivationStep.DOCUMENT_SCOPE_VERDICT
import com.dynokore.gst.canonical.domain.derivation.DerivationStep.LINE_SCOPE_AND_CATEGORY
import com.dynokore.gst.canonical.domain.derivation.DerivationStep.PACK_VERSIONS
import com.dynokore.gst.canonical.domain.derivation.DerivationStep.PARTY_IDENTIFIERS
import com.dynokore.gst.canonical.domain.derivation.DerivationStep.PRESCRIBED_PERIOD_REF
import com.dynokore.gst.canonical.domain.derivation.DerivationStep.SUBTOTALS
import com.dynokore.gst.canonical.domain.derivation.DerivationStep.TRANSACTION_DATE
import com.dynokore.gst.canonical.domain.derivation.StepOutcome.Complete
import com.dynokore.gst.canonical.domain.derivation.StepOutcome.HeldBy
import com.dynokore.gst.canonical.domain.derivation.StepOutcome.Unavailable
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DerivationGateTest {
    private fun all(vararg changes: Pair<DerivationStep, StepOutcome>): Map<DerivationStep, StepOutcome> =
        DerivationStep.entries.associateWith<DerivationStep, StepOutcome> { Complete } + changes

    private fun held(vararg codes: String) = GateDecision.Held(codes.toList())

    private val rules = Unavailable(Dependency.RULES_SVC)
    private val masterdata = Unavailable(Dependency.MASTERDATA_SVC)

    @Test
    fun `INV-AC-10446 M1 all complete proceeds, M2 holds with ING-016, M3 lists the step code first`() {
        DerivationGate.decide(all()) shouldBe GateDecision.Proceed
        DerivationGate.decide(all(PACK_VERSIONS to rules)) shouldBe held("ING-016")
        DerivationGate.decide(all(PARTY_IDENTIFIERS to masterdata, SUBTOTALS to HeldBy("PAY-099"))) shouldBe
            held("PAY-099", "ING-016")
    }

    @Test
    fun `INV-AC-1501 party identifiers unavailable holds with ING-016`() {
        DerivationGate.decide(all(PARTY_IDENTIFIERS to masterdata)) shouldBe held("ING-016")
    }

    @Test
    fun `INV-AC-1501 party identifiers held by ING-017 holds with ING-017`() {
        DerivationGate.decide(all(PARTY_IDENTIFIERS to HeldBy("ING-017"))) shouldBe held("ING-017")
    }

    @Test
    fun `INV-AC-2155 rules unavailable holds with ING-016 and a second call with all complete releases`() {
        DerivationGate.decide(all(PACK_VERSIONS to rules)) shouldBe held("ING-016")
        DerivationGate.decide(all()) shouldBe GateDecision.Proceed
    }

    @Test
    fun `INV-AC-10446 reasons follow step order`() {
        DerivationGate.decide(all(TRANSACTION_DATE to HeldBy("PER-011"), PACK_VERSIONS to rules)) shouldBe
            held("PER-011", "ING-016")
        DerivationGate.decide(
            all(PRESCRIBED_PERIOD_REF to HeldBy("PER-009"), TRANSACTION_DATE to HeldBy("PER-011")),
        ) shouldBe held("PER-011", "PER-009")
    }

    @Test
    fun `INV-AC-10446 a repeated code is kept once`() {
        DerivationGate.decide(all(PACK_VERSIONS to rules, PARTY_IDENTIFIERS to masterdata)) shouldBe held("ING-016")
        DerivationGate.decide(all(PRESCRIBED_PERIOD_REF to HeldBy("ING-016"), PACK_VERSIONS to rules)) shouldBe
            held("ING-016")
    }

    @Test
    fun `INV-AC-10446 a map missing a step is refused, also when the other seven are complete`() {
        assertThrows<IllegalArgumentException> { DerivationGate.decide(all() - SUBTOTALS) }
    }

    @Test
    fun `INV-AC-10446 RULES_SVC unavailable at SUBTOTALS is refused`() {
        assertThrows<IllegalArgumentException> { DerivationGate.decide(all(SUBTOTALS to rules)) }
    }

    @Test
    fun `INV-AC-10446 MASTERDATA_SVC unavailable at PACK_VERSIONS is refused`() {
        assertThrows<IllegalArgumentException> { DerivationGate.decide(all(PACK_VERSIONS to masterdata)) }
    }

    @Test
    fun `INV-AC-10446 RULES_SVC unavailable at PARTY_IDENTIFIERS is refused`() {
        assertThrows<IllegalArgumentException> { DerivationGate.decide(all(PARTY_IDENTIFIERS to rules)) }
    }

    @Test
    fun `INV-AC-10446 an invalid pairing is refused even when an earlier step is held`() {
        assertThrows<IllegalArgumentException> {
            DerivationGate.decide(all(DOCUMENT_FUNCTION to HeldBy("PER-011"), DOCUMENT_SCOPE_VERDICT to rules))
        }
    }

    @Test
    fun `INV-AC-10446 malformed hold codes are refused`() {
        listOf("ING016", "ing-016", "ING-16", "ING-0160", "X-016", " ING-016", "ING-016\n", "").forEach { code ->
            assertThrows<IllegalArgumentException>("code '$code'") {
                DerivationGate.decide(all(SUBTOTALS to HeldBy(code)))
            }
        }
    }

    @Test
    fun `INV-AC-10446 the exhaustive combinations of allowed outcomes decide Proceed only when all are complete`() {
        val options =
            DerivationStep.entries.map { step ->
                val allowed: List<StepOutcome> =
                    listOf(Complete, HeldBy("PER-011")) +
                        listOfNotNull(
                            rules.takeIf { step == PACK_VERSIONS || step == LINE_SCOPE_AND_CATEGORY },
                            masterdata.takeIf { step == PARTY_IDENTIFIERS },
                        )
                allowed
            }
        var combinations = listOf(emptyList<StepOutcome>())
        options.forEach { choices -> combinations = combinations.flatMap { prefix -> choices.map { prefix + it } } }
        combinations.size shouldBe 864
        combinations.forEach { picked ->
            val outcomes = DerivationStep.entries.zip(picked).toMap()
            val decision = DerivationGate.decide(outcomes)
            val allComplete = picked.all { it == Complete }
            (decision == GateDecision.Proceed) shouldBe allComplete
            if (decision is GateDecision.Held) {
                (decision.reasons.isNotEmpty()) shouldBe true
                decision.reasons.all { it == "ING-016" || it == "PER-011" } shouldBe true
            }
            DerivationGate.decide(outcomes) shouldBe decision
        }
    }

    @Test
    fun `INV-AC-10446 DerivationStep has exactly the eight members of INV-REQ-479 in order`() {
        DerivationStep.entries.map { it.name } shouldBe
            listOf(
                "DOCUMENT_FUNCTION",
                "TRANSACTION_DATE",
                "PRESCRIBED_PERIOD_REF",
                "PACK_VERSIONS",
                "LINE_SCOPE_AND_CATEGORY",
                "DOCUMENT_SCOPE_VERDICT",
                "SUBTOTALS",
                "PARTY_IDENTIFIERS",
            )
        DerivationStep.entries.map { it.label } shouldBe
            listOf(
                "documentFunction",
                "transactionDate",
                "prescribedPeriodRef",
                "pack versions",
                "line scopeVerdict and gstCategoryCode",
                "document scopeVerdict",
                "subtotals",
                "party identifiers",
            )
        Dependency.entries.map { it.name } shouldBe listOf("RULES_SVC", "MASTERDATA_SVC")
    }
}
