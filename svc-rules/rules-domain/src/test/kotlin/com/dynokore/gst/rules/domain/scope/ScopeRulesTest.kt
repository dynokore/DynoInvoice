package com.dynokore.gst.rules.domain.scope

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.domain.coding.LineCoding
import com.dynokore.gst.rules.domain.resolution.TransactionDate
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

    private fun facts(
        code: String = "SR",
        date: LocalDate = start,
        obligation: LocalDate? = start,
        regEnd: LocalDate? = null,
        direction: ScopeDirection = ScopeDirection.SALE,
        sameGroup: Boolean = false,
        claimed: Boolean? = null,
        voluntary: Boolean = false,
        line: LineCoding = LineCoding.Coded(code),
    ) = ScopeFacts(
        null,
        d(date),
        line,
        direction,
        sameGroup,
        claimed,
        voluntary,
        obligation?.let(::d),
        regEnd?.let(::d),
    )

    private fun v(
        f: ScopeFacts,
        r: ScopeRules = rules(),
    ) = r.verdictOf(f)

    @Test
    fun `INV-AC-404 INV-AC-386 INV-AC-400 the day before the obligation start is PRE_OBLIGATION`() {
        v(facts(date = start.minusDays(1))) shouldBe ScopeVerdict.PRE_OBLIGATION
    }

    @Test
    fun `INV-REQ-376 the obligation start day and the day after are submitted in scope`() {
        v(facts(date = start)) shouldBe ScopeVerdict.IN_SCOPE
        v(facts(date = start.plusDays(1))) shouldBe ScopeVerdict.IN_SCOPE
    }

    @Test
    fun `INV-REQ-378 an absent obligation start date is PRE_OBLIGATION and a present one is not`() {
        v(facts(obligation = null)) shouldBe ScopeVerdict.PRE_OBLIGATION
        v(facts(obligation = start)) shouldBe ScopeVerdict.IN_SCOPE
        v(facts(obligation = null, line = LineCoding.Refused(ErrorCode.RUL_001))) shouldBe ScopeVerdict.PRE_OBLIGATION
    }

    @Test
    fun `INV-AC-1650 a document on the registration end date is submitted`() {
        v(facts(date = end, regEnd = end)) shouldBe ScopeVerdict.IN_SCOPE
    }

    @Test
    fun `INV-AC-1557 INV-AC-387 a document after the registration end is NOT_SUBMITTABLE`() {
        v(facts(date = end.plusDays(1), regEnd = end)) shouldBe ScopeVerdict.NOT_SUBMITTABLE
        v(facts(date = end.minusDays(1), regEnd = end)) shouldBe ScopeVerdict.IN_SCOPE
        v(facts(date = end.plusDays(1), regEnd = null)) shouldBe ScopeVerdict.IN_SCOPE
    }

    @Test
    fun `INV-REQ-376 the window outranks a refused line and a refused line is NOT_SUBMITTABLE`() {
        val refused = LineCoding.Refused(ErrorCode.RUL_001)
        v(facts(line = refused)) shouldBe ScopeVerdict.NOT_SUBMITTABLE
        v(facts(line = refused, date = start.minusDays(1))) shouldBe ScopeVerdict.PRE_OBLIGATION
        v(facts(line = refused, date = end.plusDays(1), regEnd = end)) shouldBe ScopeVerdict.NOT_SUBMITTABLE
    }

    @Test
    fun `INV-REQ-738 sameGroup with a treatment other than PASS_THROUGH or none is NOT_SUBMITTABLE`() {
        v(facts(sameGroup = true), rules("OTHER")) shouldBe ScopeVerdict.NOT_SUBMITTABLE
        v(facts(sameGroup = true), rules(null)) shouldBe ScopeVerdict.NOT_SUBMITTABLE
        v(facts(sameGroup = true), rules("pass_through")) shouldBe ScopeVerdict.NOT_SUBMITTABLE
        v(facts(sameGroup = false), rules(null)) shouldBe ScopeVerdict.IN_SCOPE
        outcome(facts(sameGroup = true), rules(null)).disregardedSupplyWarning shouldBe false
    }

    @Test
    fun `INV-REQ-494 DS is NOT_SUBMITTABLE in either mode and the look-alikes are not`() {
        v(facts("DS")) shouldBe ScopeVerdict.NOT_SUBMITTABLE
        v(facts("DS", voluntary = true)) shouldBe ScopeVerdict.NOT_SUBMITTABLE
        v(facts("DSX")) shouldBe ScopeVerdict.IN_SCOPE
        v(facts("ds")) shouldBe ScopeVerdict.IN_SCOPE
    }

    @Test
    fun `INV-Q-16177 a code absent from the data is NOT_SUBMITTABLE`() {
        v(facts("ZZ")) shouldBe ScopeVerdict.NOT_SUBMITTABLE
        v(facts("ZZ", voluntary = true)) shouldBe ScopeVerdict.NOT_SUBMITTABLE
    }

    @Test
    fun `INV-REQ-1282 a purchase of a standard-rated code with no input tax claimed`() {
        val p = ScopeDirection.PURCHASE
        v(facts("TX", direction = p, claimed = false)) shouldBe ScopeVerdict.OUT_OF_SCOPE
        v(facts("TX", direction = p, claimed = false, voluntary = true)) shouldBe ScopeVerdict.SUBMITTABLE
        v(facts("TX", direction = p, claimed = null)) shouldBe ScopeVerdict.IN_SCOPE
        v(facts("TX", direction = p, claimed = null, voluntary = true)) shouldBe ScopeVerdict.IN_SCOPE
        v(facts("TX", direction = p, claimed = true)) shouldBe ScopeVerdict.IN_SCOPE
        v(facts("TX", direction = p, claimed = true, voluntary = true)) shouldBe ScopeVerdict.IN_SCOPE
        v(facts("TX", direction = ScopeDirection.SALE, claimed = false)) shouldBe ScopeVerdict.IN_SCOPE
        v(facts("TX", direction = ScopeDirection.SALE, claimed = false, voluntary = true)) shouldBe
            ScopeVerdict.IN_SCOPE
        v(facts("SR", direction = p, claimed = false)) shouldBe ScopeVerdict.IN_SCOPE
    }

    @Test
    fun `INV-REQ-1282 OS is submitted only under voluntary over-submission`() {
        v(facts("OS")) shouldBe ScopeVerdict.OUT_OF_SCOPE
        v(facts("OS", voluntary = true)) shouldBe ScopeVerdict.SUBMITTABLE
        v(facts("OSX", voluntary = true)) shouldBe ScopeVerdict.OUT_OF_SCOPE
        v(facts("OSX")) shouldBe ScopeVerdict.OUT_OF_SCOPE
    }

    @Test
    fun `INV-REQ-496 SRRC stays EXCLUDED_TXN and SR is IN_SCOPE`() {
        v(facts("SRRC")) shouldBe ScopeVerdict.EXCLUDED_TXN
        v(facts("SRRC", voluntary = true)) shouldBe ScopeVerdict.EXCLUDED_TXN
        v(facts("SR")) shouldBe ScopeVerdict.IN_SCOPE
    }

    private fun outcome(
        f: ScopeFacts,
        r: ScopeRules = rules(),
    ) = r.outcomeOf(f)

    @Test
    fun `INV-AC-761 an SR supply flagged sameGroup gets the verdict of one that is not and OS is not submitted`() {
        v(facts("SR", sameGroup = true)) shouldBe v(facts("SR", sameGroup = false))
        v(facts("SR", sameGroup = true)) shouldBe ScopeVerdict.IN_SCOPE
        v(facts("OS", sameGroup = true)) shouldBe ScopeVerdict.OUT_OF_SCOPE
    }

    @Test
    fun `INV-AC-2783 a sameGroup SR supply raises the warning and one that is not does not`() {
        outcome(facts("SR", sameGroup = true)) shouldBe ScopeOutcome(ScopeVerdict.IN_SCOPE, true)
        outcome(facts("SR", sameGroup = false)) shouldBe ScopeOutcome(ScopeVerdict.IN_SCOPE, false)
    }

    @Test
    fun `INV-REQ-720 no warning for a sameGroup code whose lineVerdict is not IN_SCOPE`() {
        outcome(facts("OS", sameGroup = true)).disregardedSupplyWarning shouldBe false
        outcome(facts("SRRC", sameGroup = true)).disregardedSupplyWarning shouldBe false
        outcome(facts("DS", sameGroup = true)).disregardedSupplyWarning shouldBe false
    }

    @Test
    fun `INV-REQ-720 no warning when the window or a refusal stops the line`() {
        outcome(facts(sameGroup = true, date = start.minusDays(1))).disregardedSupplyWarning shouldBe false
        outcome(facts(sameGroup = true, line = LineCoding.Refused(ErrorCode.RUL_001))).disregardedSupplyWarning shouldBe
            false
        outcome(facts(sameGroup = true, date = end.plusDays(1), regEnd = end)).disregardedSupplyWarning shouldBe false
    }

    @Test
    fun `INV-REQ-720 the outcome verdict equals the verdict of verdictOf for every combination`() {
        val r = rules()
        val codes = listOf("SR", "OS", "DS", "TX", "SRRC", "ZZ")
        val all =
            codes.flatMap { code ->
                listOf(false, true).flatMap { sg ->
                    listOf(false, true).flatMap { vol ->
                        listOf(null, false, true).flatMap { c ->
                            ScopeDirection.entries.map { dir ->
                                facts(code, sameGroup = sg, voluntary = vol, claimed = c, direction = dir)
                            }
                        }
                    }
                }
            }
        all.size shouldBe 144
        all.forEach { f -> r.outcomeOf(f).verdict shouldBe r.verdictOf(f) }
    }

    @Test
    fun `the default port outcome carries no warning`() {
        val port =
            object : ScopeVerdictPort {
                override fun verdictOf(facts: ScopeFacts) = ScopeVerdict.SUBMITTABLE
            }
        port.outcomeOf(facts()) shouldBe ScopeOutcome(ScopeVerdict.SUBMITTABLE, false)
    }
}
