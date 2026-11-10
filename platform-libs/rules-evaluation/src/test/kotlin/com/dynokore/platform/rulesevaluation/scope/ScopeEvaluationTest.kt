package com.dynokore.platform.rulesevaluation.scope

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.platform.rulesevaluation.EvaluationResult
import com.dynokore.platform.rulesevaluation.coding.LineCoding
import com.dynokore.platform.rulesevaluation.resolution.TransactionDate
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

/** The scope verdict of EvaluationResult is the port's verdict text as given; a missing or failing port refuses. */
class ScopeEvaluationTest {
    private val date = TransactionDate(LocalDate.of(2026, 3, 1))
    private val coded = LineCoding.Coded("SR", "9")
    private val refused = LineCoding.Refused(ErrorCode.RUL_001)
    private val base = EvaluationResult("SR", null, null)

    private fun port(verdict: ScopeVerdict) =
        object : ScopeVerdictPort {
            override fun verdictOf(facts: ScopeFacts): ScopeVerdict = verdict
        }

    @Test
    fun `INV-AC-19452 every verdict is filled as its exact upper-case text for a coded and a refused line`() {
        ScopeVerdict.entries.forEach { v ->
            listOf(coded, refused).forEach { line ->
                val r = ScopeEvaluation(port(v)).fill(base, ScopeFacts(null, date, line))
                r.scopeVerdict shouldBe v.name
                r.categoryCode shouldBe "SR"
                r.documentTypeCode shouldBe null
            }
        }
    }

    @Test
    fun `INV-AC-19452 no port is a refusal and never a verdict`() {
        listOf(coded, refused).forEach { line ->
            val e = assertThrows<ScopeRulesMissing> { ScopeEvaluation(null).fill(base, ScopeFacts(null, date, line)) }
            e.message shouldBe "no ScopeVerdictPort is configured"
        }
    }

    @Test
    fun `INV-AC-19452 a missing scope pack raised by the port is a refusal and never a verdict`() {
        val missing = IllegalStateException("no scope pack is armed on $date")
        val port =
            object : ScopeVerdictPort {
                override fun verdictOf(facts: ScopeFacts): ScopeVerdict = throw missing
            }
        val e = assertThrows<IllegalStateException> { ScopeEvaluation(port).fill(base, ScopeFacts(null, date, coded)) }
        (e === missing) shouldBe true
    }

    @Test
    fun `INV-AC-19452 an exception of the port propagates as the same instance and no default verdict appears`() {
        val boom = IllegalStateException("boom")
        val port =
            object : ScopeVerdictPort {
                override fun verdictOf(facts: ScopeFacts): ScopeVerdict = throw boom
            }
        listOf(coded, refused).forEach { line ->
            val evaluation = ScopeEvaluation(port)
            val e = assertThrows<IllegalStateException> { evaluation.scopeVerdictOf(ScopeFacts(null, date, line)) }
            (e === boom) shouldBe true
        }
    }

    @Test
    fun `INV-AC-19452 the rules decide through the port into the filled text`() {
        val rules =
            ScopeRules(ScopeRulesData(mapOf("SR" to ScopeVerdict.IN_SCOPE), "PASS_THROUGH", emptySet()))
        val facts = ScopeFacts(null, date, coded, obligationStartDate = TransactionDate(LocalDate.of(2026, 1, 1)))
        ScopeEvaluation(rules).fill(base, facts).scopeVerdict shouldBe "IN_SCOPE"
        ScopeEvaluation(rules)
            .fill(base, facts.copy(obligationStartDate = TransactionDate(LocalDate.of(2026, 4, 1))))
            .scopeVerdict shouldBe "PRE_OBLIGATION"
    }
}
