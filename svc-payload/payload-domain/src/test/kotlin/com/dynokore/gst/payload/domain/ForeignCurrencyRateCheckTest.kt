package com.dynokore.gst.payload.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class ForeignCurrencyRateCheckTest {
    private val day = LocalDate.of(2026, 10, 1)
    private val rate = BigDecimal("1.35800000")
    private val pack = RateTolerance(BigDecimal("1.5"), "percent")
    private val onFile = RateOnFile("MAS", "USD", day, rate)
    private val stated = StatedSgd(rate, "MAS", "1.00", "1.00", "1.00")

    @Suppress("LongParameterList") // a test builder: every argument of check has a default
    private fun run(
        currency: String = "USD",
        elected: String? = "MAS",
        file: RateOnFile? = onFile,
        s: StatedSgd = stated,
        p: RateTolerance = pack,
        entity: BigDecimal? = null,
        date: LocalDate = day,
    ) = ForeignCurrencyRateCheck.check(currency, date, elected, file, s, p, entity)

    private fun rejected(r: RateReason) = RateVerdict.RateRejected(r)

    @Test
    fun `INV-AC-530 INV-AC-10582 a stated rate from the elected source passes with the same record`() {
        val v = run()
        v shouldBe RateVerdict.Passed(onFile)
        (v as RateVerdict.Passed).rateUsed shouldBe onFile
    }

    @Test
    fun `INV-AC-531 INV-AC-10585 a stated rate outside tolerance is PAY-006`() {
        val v = run(s = stated.copy(exchangeRate = BigDecimal("1.40000000")))
        v shouldBe rejected(RateReason.OUTSIDE_TOLERANCE)
        (v as RateVerdict.RateRejected).code shouldBe "PAY-006"
    }

    @Test
    fun `INV-AC-2284 INV-AC-10580 a null entity value uses the pack default`() {
        run(s = stated.copy(exchangeRate = BigDecimal("1.40000000"))) shouldBe rejected(RateReason.OUTSIDE_TOLERANCE)
        run(s = stated.copy(exchangeRate = BigDecimal("1.37000000"))) shouldBe RateVerdict.Passed(onFile)
        run(s = stated.copy(exchangeRate = BigDecimal("1.37000000")), entity = BigDecimal("0.5")) shouldBe
            rejected(RateReason.OUTSIDE_TOLERANCE)
        shouldThrow<IllegalArgumentException> { run(entity = BigDecimal("2.0")) }
        shouldThrow<IllegalArgumentException> { run(entity = BigDecimal("-0.1")) }
    }

    @Test
    fun `INV-AC-532 INV-AC-10586 no rate on file is PAY-013 and passes once the rate is present`() {
        val v = run(file = null)
        v shouldBe RateVerdict.NoRateOnFile
        (v as RateVerdict.NoRateOnFile).code shouldBe "PAY-013"
        run() shouldBe RateVerdict.Passed(onFile)
        run(file = onFile.copy(rate = BigDecimal.ZERO)) shouldBe RateVerdict.NoRateOnFile
        shouldThrow<IllegalArgumentException> { run(file = onFile.copy(date = day.minusDays(1))) }
    }

    @Test
    fun `INV-AC-535 INV-AC-10583 a zero-rated export needs sgdTotalTax present and 0_00 is present`() {
        run(s = stated.copy(sgdTotalTax = "0.00")) shouldBe RateVerdict.Passed(onFile)
        val v = run(s = stated.copy(sgdTotalTax = null))
        v shouldBe RateVerdict.MissingSgdFields(listOf("sgdTotalTax"))
        (v as RateVerdict.MissingSgdFields).code shouldBe "PAY-005"
        run(s = stated.copy(sgdTotalTax = "")) shouldBe RateVerdict.MissingSgdFields(listOf("sgdTotalTax"))
    }

    @Test
    fun `INV-AC-520 INV-AC-10581 the presence rule names the absent property and SGD is not applicable`() {
        run() shouldBe RateVerdict.Passed(onFile)
        run(s = stated.copy(exchangeRateSource = " ")) shouldBe
            RateVerdict.MissingSgdFields(listOf("exchangeRateSource"))
        run(currency = "SGD", s = StatedSgd(null, null, null, null, null)) shouldBe RateVerdict.NotApplicable
    }

    @Test
    fun `three missing fields are listed in ascending order`() {
        val s = StatedSgd(null, "MAS", "1.00", null, null)
        run(s = s) shouldBe RateVerdict.MissingSgdFields(listOf("exchangeRate", "sgdTotalIncludingTax", "sgdTotalTax"))
    }

    @Test
    fun `INV-AC-10585 the limit passes and one unit more is rejected, computed with BigDecimal`() {
        val limit = rate.add(rate.multiply(pack.value).divide(BigDecimal(100)))
        limit.compareTo(BigDecimal("1.37837")) shouldBe 0
        run(s = stated.copy(exchangeRate = BigDecimal("1.37837000"))) shouldBe RateVerdict.Passed(onFile)
        run(s = stated.copy(exchangeRate = BigDecimal("1.37837001"))) shouldBe rejected(RateReason.OUTSIDE_TOLERANCE)
        val tiny = stated.copy(exchangeRate = BigDecimal("1.378370000001"))
        run(s = tiny) shouldBe rejected(RateReason.OUTSIDE_TOLERANCE)
        run(s = stated.copy(exchangeRate = BigDecimal("1.33762999"))) shouldBe rejected(RateReason.OUTSIDE_TOLERANCE)
        run(s = stated.copy(exchangeRate = BigDecimal("1.33763000"))) shouldBe RateVerdict.Passed(onFile)
    }

    @Test
    fun `INV-AC-10585 the SGD total strings are never parsed`() {
        val a = run(s = stated.copy(sgdTotalExcludingTax = "not a number", sgdTotalTax = "not a number"))
        val b = run(s = stated.copy(sgdTotalExcludingTax = "0.00", sgdTotalTax = "0.00"))
        a shouldBe b
    }

    @Test
    fun `unit other than exact percent is refused`() {
        for (u in listOf("PERCENT", "%", "bps", "percent ")) {
            shouldThrow<IllegalArgumentException> { run(p = RateTolerance(BigDecimal("1.5"), u)) }
        }
    }

    @Test
    fun `currency look-alikes of SGD are foreign`() {
        val none = StatedSgd(null, null, null, null, null)
        for (c in listOf("sgd", "SGD ")) {
            (run(currency = c, s = none) is RateVerdict.MissingSgdFields) shouldBe true
        }
        shouldThrow<IllegalArgumentException> { run(currency = " ") }
    }

    @Test
    fun `the stated source is compared exactly`() {
        for (src in listOf("mas", "MAS ")) {
            run(s = stated.copy(exchangeRateSource = src)) shouldBe rejected(RateReason.SOURCE_NOT_ELECTED)
        }
        run(s = stated.copy(exchangeRateSource = "NOMAS")) shouldBe rejected(RateReason.SOURCE_NOT_ELECTED)
    }

    @Test
    fun `INV-AC-10584 no election is PAY-016 whatever onFile holds`() {
        for (f in listOf(null, onFile)) {
            val v = run(elected = null, file = f)
            v shouldBe RateVerdict.NoElection
            (v as RateVerdict.NoElection).code shouldBe "PAY-016"
        }
    }

    @Test
    fun `an onFile of the wrong kind is never used`() {
        shouldThrow<IllegalArgumentException> { run(file = onFile.copy(source = "OTHER")) }
        shouldThrow<IllegalArgumentException> { run(file = onFile.copy(currency = "EUR")) }
        shouldThrow<IllegalArgumentException> { run(file = onFile.copy(date = day.minusDays(1))) }
    }

    @Test
    fun `stated rate 0 is not positive`() {
        run(s = stated.copy(exchangeRate = BigDecimal.ZERO)) shouldBe rejected(RateReason.STATED_RATE_NOT_POSITIVE)
    }

    @Test
    fun `RateReason has exactly three constants`() {
        RateReason.entries.map { it.name } shouldBe
            listOf("SOURCE_NOT_ELECTED", "STATED_RATE_NOT_POSITIVE", "OUTSIDE_TOLERANCE")
    }

    @Test
    fun `code constants equal the catalogue codes of error-codes yaml`() {
        ForeignCurrencyRateCheck.PAY_005 shouldBe "PAY-005"
        ForeignCurrencyRateCheck.PAY_006 shouldBe "PAY-006"
        ForeignCurrencyRateCheck.PAY_013 shouldBe "PAY-013"
        ForeignCurrencyRateCheck.PAY_016 shouldBe "PAY-016"
    }

    // ---- step order ----

    private class In(
        var currency: String = "USD",
        var unit: String = "percent",
        var entity: BigDecimal? = null,
        var s: StatedSgd,
        var elected: String? = "MAS",
        var file: RateOnFile,
    )

    private val violations: Map<Int, (In) -> Unit> =
        mapOf(
            1 to { i -> i.currency = " " },
            2 to { i -> i.currency = "SGD" },
            3 to { i -> i.unit = "bps" },
            4 to { i -> i.s = i.s.copy(sgdTotalTax = null) },
            5 to { i -> i.elected = null },
            6 to { i -> i.file = i.file.copy(rate = BigDecimal.ZERO) },
            7 to { i -> i.file = i.file.copy(source = "OTHER") },
            8 to { i -> i.s = i.s.copy(exchangeRateSource = "NOMAS") },
            9 to { i -> i.s = i.s.copy(exchangeRate = BigDecimal.ZERO) },
            10 to { i -> i.s = i.s.copy(exchangeRate = BigDecimal("1.40000000")) },
        )

    private fun assertStep(
        step: Int,
        i: In,
    ) {
        fun call() =
            ForeignCurrencyRateCheck.check(
                i.currency,
                day,
                i.elected,
                i.file,
                i.s,
                RateTolerance(BigDecimal("1.5"), i.unit),
                i.entity,
            )

        fun threw(part: String) = shouldThrow<IllegalArgumentException> { call() }.message.orEmpty() shouldContain part
        val outcomes: Map<Int, () -> Unit> =
            mapOf(
                1 to { threw("currency must not be blank") },
                2 to { call() shouldBe RateVerdict.NotApplicable },
                3 to { threw("packTolerance.unit") },
                4 to { call() shouldBe RateVerdict.MissingSgdFields(listOf("sgdTotalTax")) },
                5 to { call() shouldBe RateVerdict.NoElection },
                6 to { call() shouldBe RateVerdict.NoRateOnFile },
                7 to { threw("onFile source") },
                8 to { call() shouldBe rejected(RateReason.SOURCE_NOT_ELECTED) },
                9 to { call() shouldBe rejected(RateReason.STATED_RATE_NOT_POSITIVE) },
                10 to { call() shouldBe rejected(RateReason.OUTSIDE_TOLERANCE) },
            )
        outcomes.getValue(step)()
    }

    @Test
    fun `check step order`() {
        val skipped = mutableListOf<String>()
        var checked = 0
        for (a in 1..10) {
            for (b in a + 1..10) {
                val label = "steps $a before $b"
                if (a == 1 && b == 2) {
                    skipped += "$label (a blank currency is not SGD)"
                    continue
                }
                val input = In(s = stated, file = onFile)
                violations.getValue(b)(input)
                violations.getValue(a)(input) // the earlier step is applied last so it wins a shared field
                try {
                    assertStep(a, input)
                } catch (e: AssertionError) {
                    throw AssertionError("$label: ${e.message}", e)
                }
                checked++
            }
        }
        skipped shouldBe listOf("steps 1 before 2 (a blank currency is not SGD)")
        checked shouldBe 44
    }

    @Test
    fun `check step order named pair - an entity value above the pack value beats a mismatching onFile (3 before 7)`() {
        val e =
            shouldThrow<IllegalArgumentException> {
                run(entity = BigDecimal("2.0"), file = onFile.copy(source = "OTHER"))
            }
        e.message.orEmpty() shouldContain "entityTolerancePercent"
    }

    @Test
    fun `check step order named pairs - SGD with a bad unit and a missing field with a bad unit`() {
        val bad = RateTolerance(BigDecimal("1.5"), "bps")
        val none = StatedSgd(null, null, null, null, null)
        ForeignCurrencyRateCheck.check("SGD", day, null, null, none, bad, null) shouldBe RateVerdict.NotApplicable
        shouldThrow<IllegalArgumentException> {
            ForeignCurrencyRateCheck.check("USD", day, null, null, none, bad, null)
        }
    }
}
