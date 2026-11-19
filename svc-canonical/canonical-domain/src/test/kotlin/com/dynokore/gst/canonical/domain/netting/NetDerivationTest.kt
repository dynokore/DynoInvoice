package com.dynokore.gst.canonical.domain.netting

import com.dynokore.gst.canonical.domain.document.Direction
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class NetDerivationTest {
    private val entity = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
    private val original = UUID.fromString("00000000-0000-0000-0000-0000000000a0")
    private val idA = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val idB = UUID.fromString("00000000-0000-0000-0000-0000000000a2")

    private fun d(s: String) = BigDecimal(s)

    private fun line(
        n: Int,
        amount: String,
        code: String,
        tax: String,
    ) = NetLine(n, BigDecimal.ONE, d(amount), d(amount), code, d(tax))

    private fun totals(
        ex: String,
        tax: String,
        inc: String,
        sgd: Triple<String, String, String>? = null,
    ) = NetTotals(d(ex), d(tax), d(inc), sgd?.let { d(it.first) }, sgd?.let { d(it.second) }, sgd?.let { d(it.third) })

    private fun base(
        totals: NetTotals = totals("9000.00", "540.00", "9540.00"),
        lines: List<NetLine> = listOf(line(1, "6000.00", "TX", "540.00"), line(2, "3000.00", "ZP", "0.00")),
        subtotals: List<NetSubtotal> =
            listOf(NetSubtotal("TX", d("6000.00"), d("540.00")), NetSubtotal("ZP", d("3000.00"), d("0.00"))),
        currency: String = "SGD",
        rate: BigDecimal? = null,
    ) = NetBase(entity, Direction.PURCHASE, DocumentFunction.INVOICE, currency, rate, lines, subtotals, totals)

    @Suppress("LongParameterList")
    private fun note(
        id: UUID,
        amount: String,
        tax: String,
        code: String = "TX",
        totals: NetTotals = totals(amount, tax, (d(amount) + d(tax)).toPlainString()),
        function: DocumentFunction = DocumentFunction.CREDIT_NOTE,
        entityId: UUID = entity,
        direction: Direction = Direction.PURCHASE,
        preceding: UUID? = original,
        currency: String = "SGD",
        rate: BigDecimal? = null,
    ) = NetCreditNote(
        id,
        1,
        preceding,
        entityId,
        direction,
        function,
        currency,
        rate,
        listOf(line(1, amount, code, tax)),
        listOf(NetSubtotal(code, d(amount), d(tax))),
        totals,
    )

    private fun derived(r: NetResult) = r as Derived

    private fun refused(r: NetResult) = (r as Refused).reason

    @Test
    fun `INV-AC-25339 net of one credit note`() {
        val r = derived(NetDerivation.derive(original, base(), listOf(note(idA, "1000.00", "90.00"))))
        r.lines.size shouldBe 3
        r.lines.take(2) shouldBe base().lines
        r.lines[2] shouldBe NetLine(3, BigDecimal.ONE, d("-1000.00"), d("-1000.00"), "TX", d("-90.00"))
        r.subtotals shouldBe
            listOf(NetSubtotal("TX", d("5000.00"), d("450.00")), NetSubtotal("ZP", d("3000.00"), d("0.00")))
        r.totals shouldBe totals("8000.00", "450.00", "8450.00")
    }

    @Test
    fun `INV-AC-25339 two credit notes give equal results in both list orders`() {
        val a = note(idA, "1000.00", "90.00")
        val b = note(idB, "500.00", "45.00")
        val ab = derived(NetDerivation.derive(original, base(), listOf(a, b)))
        val ba = derived(NetDerivation.derive(original, base(), listOf(b, a)))
        ab shouldBe ba
        ab.totals shouldBe totals("7500.00", "405.00", "7905.00")
        ab.lines.map { it.lineNumber to it.lineAmount } shouldBe
            listOf(1 to d("6000.00"), 2 to d("3000.00"), 3 to d("-1000.00"), 4 to d("-500.00"))
    }

    @Test
    fun `INV-AC-25339 stated totals that differ from the line sums keep the difference`() {
        val b = base(totals = totals("9100.00", "540.00", "9640.00"))
        val r = derived(NetDerivation.derive(original, b, listOf(note(idA, "1000.00", "90.00"))))
        r.totals shouldBe totals("8100.00", "450.00", "8550.00")
    }

    @Test
    fun `INV-AC-25339 SGD totals are subtracted when stated on both sides`() {
        val sgd = Triple("12000.00", "720.00", "12720.00")
        val b = base(totals = totals("9000.00", "540.00", "9540.00", sgd), currency = "USD", rate = d("1.3333"))
        val n =
            note(
                idA,
                "1000.00",
                "90.00",
                totals = totals("1000.00", "90.00", "1090.00", Triple("1333.00", "120.00", "1453.00")),
                currency = "USD",
                rate = d("1.3333"),
            )
        val r = derived(NetDerivation.derive(original, b, listOf(n)))
        r.totals shouldBe totals("8000.00", "450.00", "8450.00", Triple("10667.00", "600.00", "11267.00"))
    }

    @Test
    fun `INV-AC-25339 SGD totals on one side only are CURRENCY_MISMATCH`() {
        val b = base(totals = totals("9000.00", "540.00", "9540.00", Triple("1.00", "1.00", "2.00")))
        refused(NetDerivation.derive(original, b, listOf(note(idA, "1000.00", "90.00")))) shouldBe
            NetRefusal.CURRENCY_MISMATCH
    }

    @Test
    fun `INV-AC-25339 refusal NOT_A_CREDIT_NOTE`() {
        val n = note(idA, "1000.00", "90.00", function = DocumentFunction.DEBIT_NOTE)
        refused(NetDerivation.derive(original, base(), listOf(n))) shouldBe NetRefusal.NOT_A_CREDIT_NOTE
    }

    @Test
    fun `INV-AC-25339 refusal OTHER_ENTITY for entity and for direction`() {
        val other = UUID.fromString("00000000-0000-0000-0000-0000000000e2")
        refused(
            NetDerivation.derive(original, base(), listOf(note(idA, "1000.00", "90.00", entityId = other))),
        ) shouldBe
            NetRefusal.OTHER_ENTITY
        refused(
            NetDerivation.derive(original, base(), listOf(note(idA, "1000.00", "90.00", direction = Direction.SALE))),
        ) shouldBe NetRefusal.OTHER_ENTITY
    }

    @Test
    fun `INV-AC-25339 refusal NOT_PRECEDING for absent and for other original`() {
        refused(
            NetDerivation.derive(original, base(), listOf(note(idA, "1000.00", "90.00", preceding = null))),
        ) shouldBe
            NetRefusal.NOT_PRECEDING
        refused(NetDerivation.derive(original, base(), listOf(note(idA, "1000.00", "90.00", preceding = idB)))) shouldBe
            NetRefusal.NOT_PRECEDING
    }

    @Test
    fun `INV-AC-25339 refusal CURRENCY_MISMATCH for currency and for exchange rate`() {
        refused(
            NetDerivation.derive(original, base(), listOf(note(idA, "1000.00", "90.00", currency = "USD"))),
        ) shouldBe
            NetRefusal.CURRENCY_MISMATCH
        refused(NetDerivation.derive(original, base(), listOf(note(idA, "1000.00", "90.00", rate = d("1.2"))))) shouldBe
            NetRefusal.CURRENCY_MISMATCH
    }

    @Test
    fun `INV-AC-25339 NOT_PRECEDING wins over CURRENCY_MISMATCH`() {
        val n = note(idA, "1000.00", "90.00", preceding = null, currency = "USD")
        refused(NetDerivation.derive(original, base(), listOf(n))) shouldBe NetRefusal.NOT_PRECEDING
    }

    @Test
    fun `INV-AC-25339 refusal NET_NOT_POSITIVE when the net is exactly 0_00`() {
        // every group nets to 0.00 and the total including tax is exactly 0.00: only the zero boundary refuses
        val b =
            base(
                totals = totals("100.00", "9.00", "109.00"),
                lines = listOf(line(1, "100.00", "TX", "9.00")),
                subtotals = listOf(NetSubtotal("TX", d("100.00"), d("9.00"))),
            )
        refused(NetDerivation.derive(original, b, listOf(note(idA, "100.00", "9.00")))) shouldBe
            NetRefusal.NET_NOT_POSITIVE
    }

    @Test
    fun `INV-AC-25339 a negative ZP group with a positive total is refused`() {
        val n = note(idA, "3500.00", "0.00", code = "ZP", totals = totals("100.00", "0.00", "100.00"))
        refused(NetDerivation.derive(original, base(), listOf(n))) shouldBe NetRefusal.NET_NOT_POSITIVE
    }

    @Test
    fun `INV-AC-25339 a taxCode look-alike TX7 stays its own group`() {
        // merged into TX the net would be positive; as its own group TX7 is negative, so the net is refused
        val n = note(idA, "1000.00", "90.00", code = "TX7")
        refused(NetDerivation.derive(original, base(), listOf(n))) shouldBe NetRefusal.NET_NOT_POSITIVE
        val b =
            base(
                lines = base().lines + line(3, "2000.00", "TX7", "180.00"),
                subtotals = base().subtotals + NetSubtotal("TX7", d("2000.00"), d("180.00")),
            )
        val r = derived(NetDerivation.derive(original, b, listOf(n)))
        r.subtotals.map { it.taxCode } shouldBe listOf("TX", "ZP", "TX7")
        r.subtotals[0] shouldBe NetSubtotal("TX", d("6000.00"), d("540.00"))
        r.subtotals[2] shouldBe NetSubtotal("TX7", d("1000.00"), d("90.00"))
    }

    @Test
    fun `INV-AC-25339 empty list and repeated docUuid throw`() {
        shouldThrow<IllegalArgumentException> { NetDerivation.derive(original, base(), emptyList()) }
        val a = note(idA, "1000.00", "90.00")
        shouldThrow<IllegalArgumentException> { NetDerivation.derive(original, base(), listOf(a, a)) }
    }

    @Test
    fun `INV-AC-25339 an operand with more than 2 fractional digits throws`() {
        val n = note(idA, "1000.001", "90.00")
        shouldThrow<IllegalArgumentException> { NetDerivation.derive(original, base(), listOf(n)) }
    }

    @Test
    fun `INV-AC-25339 NetRefusal has exactly the seven names`() {
        NetRefusal.entries.map { it.name }.toSet() shouldBe
            setOf(
                "NOT_A_CREDIT_NOTE",
                "OTHER_ENTITY",
                "CURRENCY_MISMATCH",
                "NOT_PRECEDING",
                "BASE_CHANGED",
                "DOCUMENT_NOT_FOUND",
                "NET_NOT_POSITIVE",
            )
    }
}
