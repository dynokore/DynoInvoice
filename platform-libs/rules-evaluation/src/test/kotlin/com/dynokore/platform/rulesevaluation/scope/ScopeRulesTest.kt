package com.dynokore.platform.rulesevaluation.scope

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.platform.rulesevaluation.coding.LineCoding
import com.dynokore.platform.rulesevaluation.resolution.TransactionDate
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ScopeRulesTest {
    private val start = LocalDate.of(2026, 3, 1)
    private val end = LocalDate.of(2026, 9, 30)

    private fun d(x: LocalDate) = TransactionDate(x)

    private fun rules(treatment: String? = "PASS_THROUGH") =
        ScopeRules(
            ScopeRulesData(
                lineVerdicts =
                    mapOf(
                        "SR" to ScopeVerdict.IN_SCOPE,
                        "ZR" to ScopeVerdict.IN_SCOPE,
                        "OS" to ScopeVerdict.OUT_OF_SCOPE,
                        "SRRC" to ScopeVerdict.EXCLUDED_TXN,
                        "DS" to ScopeVerdict.IN_SCOPE,
                        "TX" to ScopeVerdict.IN_SCOPE,
                        "ds" to ScopeVerdict.IN_SCOPE,
                        "DSX" to ScopeVerdict.IN_SCOPE,
                        "OSX" to ScopeVerdict.OUT_OF_SCOPE,
                    ),
                intraGroupTreatment = treatment,
                purchaseStandardRatedCodes = setOf("TX"),
            ),
        )

    private data class Case(
        val code: String = "SR",
        val date: LocalDate = LocalDate.of(2026, 3, 1),
        val obligation: LocalDate? = LocalDate.of(2026, 3, 1),
        val regEnd: LocalDate? = null,
        val direction: ScopeDirection = ScopeDirection.SALE,
        val sameGroup: Boolean = false,
        val claimed: Boolean? = null,
        val voluntary: Boolean = false,
        val line: LineCoding = LineCoding.Coded(code),
    )

    private fun facts(c: Case) =
        ScopeFacts(
            null,
            d(c.date),
            c.line,
            c.direction,
            c.sameGroup,
            c.claimed,
            c.voluntary,
            c.obligation?.let(::d),
            c.regEnd?.let(::d),
        )

    private fun v(
        f: ScopeFacts,
        r: ScopeRules = rules(),
    ) = r.verdictOf(f)

    @Test
    fun `INV-AC-19452 INV-AC-404 INV-AC-386 INV-AC-400 the day before the obligation start is PRE_OBLIGATION`() {
        v(facts(Case(date = start.minusDays(1)))) shouldBe ScopeVerdict.PRE_OBLIGATION
    }

    @Test
    fun `INV-AC-19452 INV-REQ-376 the obligation start day and the day after are submitted in scope`() {
        v(facts(Case(date = start))) shouldBe ScopeVerdict.IN_SCOPE
        v(facts(Case(date = start.plusDays(1)))) shouldBe ScopeVerdict.IN_SCOPE
    }

    @Test
    fun `INV-AC-19452 INV-REQ-378 an absent obligation start date is PRE_OBLIGATION and a present one is not`() {
        v(facts(Case(obligation = null))) shouldBe ScopeVerdict.PRE_OBLIGATION
        v(facts(Case(obligation = start))) shouldBe ScopeVerdict.IN_SCOPE
        v(facts(Case(obligation = null, line = LineCoding.Refused(ErrorCode.RUL_001)))) shouldBe
            ScopeVerdict.PRE_OBLIGATION
    }

    @Test
    fun `INV-AC-19452 INV-AC-1650 a document on the registration end date is submitted`() {
        v(facts(Case(date = end, regEnd = end))) shouldBe ScopeVerdict.IN_SCOPE
    }

    @Test
    fun `INV-AC-19452 INV-AC-1557 INV-AC-387 a document after the registration end is NOT_SUBMITTABLE`() {
        v(facts(Case(date = end.plusDays(1), regEnd = end))) shouldBe ScopeVerdict.NOT_SUBMITTABLE
        v(facts(Case(date = end.minusDays(1), regEnd = end))) shouldBe ScopeVerdict.IN_SCOPE
        v(facts(Case(date = end.plusDays(1), regEnd = null))) shouldBe ScopeVerdict.IN_SCOPE
    }

    @Test
    fun `INV-AC-19452 INV-REQ-376 the window outranks a refused line and a refused line is NOT_SUBMITTABLE`() {
        val refused = LineCoding.Refused(ErrorCode.RUL_001)
        v(facts(Case(line = refused))) shouldBe ScopeVerdict.NOT_SUBMITTABLE
        v(facts(Case(line = refused, date = start.minusDays(1)))) shouldBe ScopeVerdict.PRE_OBLIGATION
        v(facts(Case(line = refused, date = end.plusDays(1), regEnd = end))) shouldBe ScopeVerdict.NOT_SUBMITTABLE
    }

    @Test
    fun `INV-AC-19452 INV-REQ-738 sameGroup with a treatment other than PASS_THROUGH or none is NOT_SUBMITTABLE`() {
        v(facts(Case(sameGroup = true)), rules("OTHER")) shouldBe ScopeVerdict.NOT_SUBMITTABLE
        v(facts(Case(sameGroup = true)), rules(null)) shouldBe ScopeVerdict.NOT_SUBMITTABLE
        v(facts(Case(sameGroup = true)), rules("pass_through")) shouldBe ScopeVerdict.NOT_SUBMITTABLE
        v(facts(Case(sameGroup = false)), rules(null)) shouldBe ScopeVerdict.IN_SCOPE
        outcome(facts(Case(sameGroup = true)), rules(null)).disregardedSupplyWarning shouldBe false
    }

    @Test
    fun `INV-AC-19452 INV-REQ-494 DS is EXCLUDED_TXN in either mode and the look-alikes are not`() {
        v(facts(Case("DS"))) shouldBe ScopeVerdict.EXCLUDED_TXN
        v(facts(Case("DS", voluntary = true))) shouldBe ScopeVerdict.EXCLUDED_TXN
        v(facts(Case("DSX"))) shouldBe ScopeVerdict.IN_SCOPE
        v(facts(Case("ds"))) shouldBe ScopeVerdict.IN_SCOPE
    }

    @Test
    fun `INV-AC-19452 INV-Q-16177 a code absent from the data is NOT_SUBMITTABLE`() {
        v(facts(Case("ZZ"))) shouldBe ScopeVerdict.NOT_SUBMITTABLE
        v(facts(Case("ZZ", voluntary = true))) shouldBe ScopeVerdict.NOT_SUBMITTABLE
    }

    @Test
    fun `INV-AC-19452 INV-REQ-1282 a purchase of a standard-rated code with no input tax claimed`() {
        val p = ScopeDirection.PURCHASE
        v(facts(Case("TX", direction = p, claimed = false))) shouldBe ScopeVerdict.OUT_OF_SCOPE
        v(facts(Case("TX", direction = p, claimed = false, voluntary = true))) shouldBe ScopeVerdict.SUBMITTABLE
        v(facts(Case("TX", direction = p, claimed = null))) shouldBe ScopeVerdict.IN_SCOPE
        v(facts(Case("TX", direction = p, claimed = null, voluntary = true))) shouldBe ScopeVerdict.IN_SCOPE
        v(facts(Case("TX", direction = p, claimed = true))) shouldBe ScopeVerdict.IN_SCOPE
        v(facts(Case("TX", direction = p, claimed = true, voluntary = true))) shouldBe ScopeVerdict.IN_SCOPE
        v(facts(Case("TX", direction = ScopeDirection.SALE, claimed = false))) shouldBe ScopeVerdict.IN_SCOPE
        v(facts(Case("TX", direction = ScopeDirection.SALE, claimed = false, voluntary = true))) shouldBe
            ScopeVerdict.IN_SCOPE
        v(facts(Case("SR", direction = p, claimed = false))) shouldBe ScopeVerdict.IN_SCOPE
    }

    @Test
    fun `INV-AC-19452 INV-REQ-1282 OS is submitted only under voluntary over-submission`() {
        v(facts(Case("OS"))) shouldBe ScopeVerdict.OUT_OF_SCOPE
        v(facts(Case("OS", voluntary = true))) shouldBe ScopeVerdict.SUBMITTABLE
        v(facts(Case("OSX", voluntary = true))) shouldBe ScopeVerdict.OUT_OF_SCOPE
        v(facts(Case("OSX"))) shouldBe ScopeVerdict.OUT_OF_SCOPE
    }

    @Test
    fun `INV-AC-19452 INV-REQ-496 SRRC stays EXCLUDED_TXN and SR is IN_SCOPE`() {
        v(facts(Case("SRRC"))) shouldBe ScopeVerdict.EXCLUDED_TXN
        v(facts(Case("SRRC", voluntary = true))) shouldBe ScopeVerdict.EXCLUDED_TXN
        v(facts(Case("SR"))) shouldBe ScopeVerdict.IN_SCOPE
    }

    private fun outcome(
        f: ScopeFacts,
        r: ScopeRules = rules(),
    ) = r.outcomeOf(f)

    @Test
    fun `INV-AC-19452 INV-AC-761 a sameGroup SR supply gets the verdict of one that is not and OS is not submitted`() {
        v(facts(Case("SR", sameGroup = true))) shouldBe v(facts(Case("SR", sameGroup = false)))
        v(facts(Case("SR", sameGroup = true))) shouldBe ScopeVerdict.IN_SCOPE
        v(facts(Case("OS", sameGroup = true))) shouldBe ScopeVerdict.OUT_OF_SCOPE
    }

    @Test
    fun `INV-AC-19452 INV-AC-2783 a sameGroup SR supply raises the warning and one that is not does not`() {
        outcome(facts(Case("SR", sameGroup = true))) shouldBe ScopeOutcome(ScopeVerdict.IN_SCOPE, true)
        outcome(facts(Case("SR", sameGroup = false))) shouldBe ScopeOutcome(ScopeVerdict.IN_SCOPE, false)
    }

    @Test
    fun `INV-AC-19452 INV-REQ-720 no warning for a sameGroup code whose lineVerdict is not IN_SCOPE`() {
        outcome(facts(Case("OS", sameGroup = true))).disregardedSupplyWarning shouldBe false
        outcome(facts(Case("SRRC", sameGroup = true))).disregardedSupplyWarning shouldBe false
        outcome(facts(Case("DS", sameGroup = true))).disregardedSupplyWarning shouldBe false
    }

    @Test
    fun `INV-AC-19452 INV-REQ-720 no warning when the window or a refusal stops the line`() {
        outcome(facts(Case(sameGroup = true, date = start.minusDays(1)))).disregardedSupplyWarning shouldBe false
        outcome(
            facts(Case(sameGroup = true, line = LineCoding.Refused(ErrorCode.RUL_001))),
        ).disregardedSupplyWarning shouldBe
            false
        outcome(facts(Case(sameGroup = true, date = end.plusDays(1), regEnd = end))).disregardedSupplyWarning shouldBe
            false
    }

    @Test
    fun `INV-AC-19452 INV-REQ-720 the outcome verdict equals the verdict of verdictOf for every combination`() {
        val r = rules()
        val codes = listOf("SR", "OS", "DS", "TX", "SRRC", "ZZ")
        val all =
            codes.flatMap { code ->
                listOf(false, true).flatMap { sg ->
                    listOf(false, true).flatMap { vol ->
                        listOf(null, false, true).flatMap { c ->
                            ScopeDirection.entries.map { dir ->
                                facts(Case(code, sameGroup = sg, voluntary = vol, claimed = c, direction = dir))
                            }
                        }
                    }
                }
            }
        all.size shouldBe 144
        all.forEach { f -> r.outcomeOf(f).verdict shouldBe r.verdictOf(f) }
    }

    @Test
    fun `INV-AC-19452 the default port outcome carries no warning`() {
        val port =
            object : ScopeVerdictPort {
                override fun verdictOf(facts: ScopeFacts) = ScopeVerdict.SUBMITTABLE
            }
        port.outcomeOf(facts(Case())) shouldBe ScopeOutcome(ScopeVerdict.SUBMITTABLE, false)
    }
}
