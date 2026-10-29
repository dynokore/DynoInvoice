package com.dynokore.gst.rules.application

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.domain.coding.CatalogueEntry
import com.dynokore.gst.rules.domain.coding.CodeCatalogue
import com.dynokore.gst.rules.domain.coding.Direction
import com.dynokore.gst.rules.domain.coding.Handling
import com.dynokore.gst.rules.domain.coding.LineInput
import com.dynokore.gst.rules.domain.coding.MappingEntry
import com.dynokore.gst.rules.domain.coding.RateBasis
import com.dynokore.gst.rules.domain.coding.ScenarioData
import com.dynokore.gst.rules.domain.coding.SchemeTagSide
import com.dynokore.gst.rules.domain.coding.SelfBillingAssignment
import com.dynokore.gst.rules.domain.coding.Side
import com.dynokore.gst.rules.domain.coding.SubmissionType
import com.dynokore.gst.rules.domain.coding.Treatment
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate

/** Document coding over fixture ports. Every rate, date and code below is invented test data, not IRAS data. */
class DocumentCodingTest {
    private val changeDate = LocalDate.of(2026, 7, 1)
    private val firstDate = LocalDate.of(2026, 1, 1)

    private fun entry(
        code: String,
        rate: String,
        submission: String? = null,
    ) = CatalogueEntry(code, "TEST", "SUPPLY", rate, submission, null, null)

    private fun catalogue(
        standard: String,
        vararg more: CatalogueEntry,
    ) = CodeCatalogue(
        true,
        listOf(entry("SR", standard), entry("ZR", "0"), entry("TXCA", standard), entry("SRCA-S", "0")) + more,
    )

    /** Two catalogue versions: rate 9 from 2026-01-01, rate 10 from 2026-07-01 (test data). */
    private fun catalogueAt(
        date: LocalDate,
        extra: List<CatalogueEntry> = emptyList(),
    ): CodeCatalogue? =
        when {
            date < firstDate -> null
            date < changeDate -> catalogue("9", *extra.toTypedArray())
            else -> catalogue("10", *extra.toTypedArray())
        }

    private val selfBilling = SelfBillingAssignment(Direction.SALE, SubmissionType.TYPE_1A)
    private val scenario =
        ScenarioData(
            selfBilling,
            selfBilling,
            listOf(
                SchemeTagSide("CA", Side.SUPPLY, Handling.HANDLED),
                SchemeTagSide("CA", Side.PURCHASE, Handling.HANDLED),
            ),
            listOf(
                Treatment("CA", Side.SUPPLY, "SRCA-S", RateBasis.FIXED, "0", emptySet(), null, null),
                Treatment("CA", Side.PURCHASE, "TXCA", RateBasis.PREVAILING, null, emptySet(), null, null),
            ),
        )

    private val mapping =
        listOf(
            MappingEntry("STD", "SR"),
            MappingEntry("EXP", "ZR"),
            MappingEntry("CA", "SR", "CA"),
            MappingEntry("UNK", "QQ"),
        )

    private fun coder(
        extra: List<CatalogueEntry> = emptyList(),
        entries: List<MappingEntry> = mapping,
    ) = DocumentCoder(
        { _, d -> if (d.value < firstDate) null else entries },
        { _, d -> catalogueAt(d.value, extra) },
        { _, d -> if (d.value < firstDate) null else scenario },
    )

    private fun line(
        id: String,
        amount: String,
        code: String,
        direction: Direction? = null,
    ) = DocumentLine(id, BigDecimal(amount), LineInput(code, direction, election = "x"))

    private fun command(
        date: LocalDate,
        vararg lines: DocumentLine,
    ) = CodeDocumentCommand(null, TransactionDate(date), lines.toList())

    private fun coded(result: DocumentCodingResult) = result.shouldBeInstanceOf<DocumentCodingResult.Coded>().document

    @Test
    fun `a back-dated line takes the older rate`() {
        val doc = coded(coder().code(command(LocalDate.of(2026, 3, 1), line("1", "100.00", "STD"))))
        doc.subtotals
            .single()
            .rate
            .compareTo(BigDecimal("9")) shouldBe 0
        doc.totalGst shouldBe BigDecimal("9.00")
    }

    @Test
    fun `the same line after the change date takes the newer rate`() {
        val doc = coded(coder().code(command(changeDate, line("1", "100.00", "STD"))))
        doc.subtotals
            .single()
            .rate
            .compareTo(BigDecimal("10")) shouldBe 0
        doc.totalGst shouldBe BigDecimal("10.00")
    }

    @Test
    fun `a date before both versions gives RUL-002`() {
        val refused = coder().code(command(LocalDate.of(2025, 12, 31), line("1", "100.00", "STD")))
        refused.shouldBeInstanceOf<DocumentCodingResult.Refused>().code shouldBe ErrorCode.RUL_002
    }

    // INV-AC-744
    @Test
    fun `SR SR and ZR give two groups with correct totals`() {
        val doc =
            coded(
                coder().code(
                    command(
                        LocalDate.of(2026, 3, 1),
                        line("1", "100.00", "STD"),
                        line("2", "200.00", "STD"),
                        line("3", "400.00", "EXP"),
                    ),
                ),
            )
        doc.subtotals.map { it.categoryCode } shouldBe listOf("SR", "ZR")
        doc.subtotals[0].taxable shouldBe BigDecimal("300.00")
        doc.subtotals[0].tax shouldBe BigDecimal("27.00")
        doc.subtotals[1].taxable shouldBe BigDecimal("400.00")
        doc.subtotals[1].tax.compareTo(BigDecimal.ZERO) shouldBe 0
        doc.totalWithoutGst shouldBe BigDecimal("700.00")
        doc.totalGst shouldBe BigDecimal("27.00")
        doc.totalWithGst shouldBe BigDecimal("727.00")
    }

    // INV-AC-1555
    @Test
    fun `each line gets its code and lines in another order give the same groups`() {
        val a = line("1", "100.00", "STD")
        val b = line("2", "200.00", "STD")
        val c = line("3", "400.00", "EXP")
        val date = LocalDate.of(2026, 3, 1)
        val forward = coded(coder().code(command(date, a, b, c)))
        val shuffled = coded(coder().code(command(date, c, a, b)))
        forward.lines.associate { it.lineId to it.categoryCode } shouldBe mapOf("1" to "SR", "2" to "SR", "3" to "ZR")
        shuffled.subtotals shouldBe forward.subtotals
    }

    @Test
    fun `an unmapped internal code is refused and never defaulted to SR`() {
        val result = coder().code(command(changeDate, line("1", "10.00", "NOPE")))
        result.shouldBeInstanceOf<DocumentCodingResult.LinesRefused>().refusals shouldBe mapOf("1" to ErrorCode.RUL_001)
    }

    // INV-AC-390
    @Test
    fun `supplier SRCA-S at 0 and customer TXCA at the prevailing rate with no divergence finding`() {
        val date = LocalDate.of(2026, 3, 1)
        val supplier = coded(coder().code(command(date, line("1", "100.00", "CA", Direction.SALE))))
        val customer = coded(coder().code(command(date, line("1", "100.00", "CA", Direction.PURCHASE))))
        supplier.subtotals.single().categoryCode shouldBe "SRCA-S"
        supplier.subtotals
            .single()
            .rate
            .compareTo(BigDecimal.ZERO) shouldBe 0
        customer.subtotals.single().categoryCode shouldBe "TXCA"
        customer.subtotals
            .single()
            .rate
            .compareTo(BigDecimal("9")) shouldBe 0
        supplier.totalGst shouldBe BigDecimal("0.00")
        customer.totalGst shouldBe BigDecimal("9.00")
    }

    @Test
    fun `NA annexERate takes the submissionRate`() {
        val extra = listOf(entry("NAX", "NA", "7"))
        val doc =
            coded(coder(extra, listOf(MappingEntry("N", "NAX"))).code(command(changeDate, line("1", "100.00", "N"))))
        doc.subtotals
            .single()
            .rate
            .compareTo(BigDecimal("7")) shouldBe 0
    }

    @Test
    fun `an annexERate neither numeric nor NA stops with a failure and no default rate`() {
        val extra = listOf(entry("BAD", "nine", "7"))
        assertThrows<DocumentCodingFailure> {
            coder(extra, listOf(MappingEntry("B", "BAD"))).code(command(changeDate, line("1", "1.00", "B")))
        }
    }

    @Test
    fun `NA without a numeric submissionRate stops with a failure`() {
        assertThrows<DocumentCodingFailure> {
            coder(
                listOf(entry("NAX", "NA")),
                listOf(MappingEntry("N", "NAX")),
            ).code(command(changeDate, line("1", "1.00", "N")))
        }
        assertThrows<DocumentCodingFailure> {
            coder(
                listOf(entry("NAX", "NA", "x")),
                listOf(MappingEntry("N", "NAX")),
            ).code(command(changeDate, line("1", "1.00", "N")))
        }
    }
}
