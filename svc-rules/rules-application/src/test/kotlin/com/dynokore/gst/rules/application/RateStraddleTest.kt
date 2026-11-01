package com.dynokore.gst.rules.application

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.domain.coding.CatalogueEntry
import com.dynokore.gst.rules.domain.coding.CodeCatalogue
import com.dynokore.gst.rules.domain.coding.Direction
import com.dynokore.gst.rules.domain.coding.Handling
import com.dynokore.gst.rules.domain.coding.LineInput
import com.dynokore.gst.rules.domain.coding.MappingEntry
import com.dynokore.gst.rules.domain.coding.ScenarioData
import com.dynokore.gst.rules.domain.coding.SchemeTagSide
import com.dynokore.gst.rules.domain.coding.SelfBillingAssignment
import com.dynokore.gst.rules.domain.coding.Side
import com.dynokore.gst.rules.domain.coding.SubmissionType
import com.dynokore.gst.rules.domain.resolution.PackResolution
import com.dynokore.gst.rules.domain.resolution.PackResolver
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.gst.rules.domain.resolution.ResolutionDates
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.rulepackkernel.InMemoryPackRegistry
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackManifest
import com.dynokore.platform.rulepackkernel.PackVersion
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Rate resolution across a rate change (INV-REQ-684, INV-REQ-685, INV-REQ-701, INV-REQ-702) over a two-rate fixture
 * pack: rate 9 before 2026-07-01 and rate 10 from it. Every rate, date, number and code is invented test data.
 */
class RateStraddleTest {
    private val change = LocalDate.of(2026, 7, 1)
    private val before = change.minusDays(1)
    private val invoiceDate = LocalDate.of(2026, 6, 20)
    private val creditDate = LocalDate.of(2026, 7, 15)

    private fun catalogue(rate: String) =
        CodeCatalogue(true, listOf(CatalogueEntry("SR", "TEST", "SUPPLY", rate, null, null, null)))

    private val selfBilling = SelfBillingAssignment(Direction.SALE, SubmissionType.TYPE_1A)
    private val scenario =
        ScenarioData(selfBilling, selfBilling, listOf(SchemeTagSide("X", Side.SUPPLY, Handling.HANDLED)), emptyList())

    private val coder =
        DocumentCoder(
            { _, _ -> listOf(MappingEntry("STD", "SR")) },
            { _, d -> if (d.value < change) catalogue("9") else catalogue("10") },
            { _, _ -> scenario },
        )

    private val known = mapOf("INV-100" to TransactionDate(invoiceDate))
    private val lookup = PrecedingDocumentLookup { _, number -> known[number] }
    private val creditNotes = CreditNoteCoding(coder, lookup)

    private val line =
        DocumentLine("1", BigDecimal("100.00"), BigDecimal("0.00"), LineInput("STD", null, election = "x"))

    private fun invoice(date: LocalDate) =
        coder
            .code(CodeDocumentCommand(null, TransactionDate(date), listOf(line)))
            .shouldBeInstanceOf<DocumentCodingResult.Coded>()

    private fun creditNote(
        own: LocalDate,
        number: String? = null,
        date: LocalDate? = null,
    ) = creditNotes.code(
        CodeCreditNoteCommand(null, TransactionDate(own), number, date?.let(::TransactionDate), listOf(line)),
    )

    private fun CreditNoteCodingResult.coded() = shouldBeInstanceOf<CreditNoteCodingResult.Coded>()

    @Test
    fun `INV-AC-1747 documents at D-1 and D resolve the rate of their own date and record the pack version`() {
        val registry = InMemoryPackRegistry()

        fun rate(
            label: String,
            from: LocalDate,
            to: LocalDate?,
        ) = registry.register(
            PackVersion(
                PackManifest(PackId("catalogue"), PackTypes.CODE_CATALOGUE, PackVersionLabel(label), from, to, "1.0.0"),
            ),
        )
        rate("rate-9", LocalDate.of(2026, 1, 1), change)
        rate("rate-10", change, null)
        val resolver = PackResolver(registry)

        fun applied(date: LocalDate) =
            resolver
                .resolve(listOf(PackTypes.CODE_CATALOGUE), null, ResolutionDates(TransactionDate(date)))
                .shouldBeInstanceOf<PackResolution.Resolved>()
                .binding
                .appliedFor(PackTypes.CODE_CATALOGUE)!!
                .version.value
        // both are processed on the same day: only the document's date selects the version
        applied(before) shouldBe "rate-9"
        applied(change) shouldBe "rate-10"
        invoice(before)
            .document.lines
            .single()
            .rate
            .compareTo(BigDecimal("9")) shouldBe 0
        invoice(change)
            .document.lines
            .single()
            .rate
            .compareTo(BigDecimal("10")) shouldBe 0
    }

    @Test
    fun `INV-AC-746 a linked credit note takes rate and code from the original's date and names both dates`() {
        val result = creditNote(creditDate, "INV-100", invoiceDate).coded()
        val coded = result.document.lines.single()
        coded.rate.compareTo(BigDecimal("9")) shouldBe 0
        coded.categoryCode shouldBe "SR"
        result.trace.ownDate.value shouldBe creditDate
        result.trace.precedingDate!!.value shouldBe invoiceDate
        result.trace.rateDate.value shouldBe invoiceDate
        result.trace.basis shouldBe RateDateBasis.PRECEDING_DATE_STATED
        // the invoice itself and the credit note tally; the credit note's own date would give the new rate
        invoice(invoiceDate)
            .document.lines
            .single()
            .rate
            .compareTo(coded.rate) shouldBe 0
        invoice(creditDate)
            .document.lines
            .single()
            .rate
            .compareTo(BigDecimal("10")) shouldBe 0
    }

    @Test
    fun `INV-AC-746 a credit note before the change linked to an original after it resolves at the original's date`() {
        val result = creditNote(before, "INV-100", change.plusDays(3)).coded()
        result.document.lines
            .single()
            .rate
            .compareTo(BigDecimal("10")) shouldBe 0
        result.trace.rateDate.value shouldBe change.plusDays(3)
    }

    @Test
    fun `INV-AC-746 a credit note with only the number takes the original's date from the entity's document`() {
        val result = creditNote(creditDate, "INV-100").coded()
        result.document.lines
            .single()
            .rate
            .compareTo(BigDecimal("9")) shouldBe 0
        result.trace.basis shouldBe RateDateBasis.PRECEDING_DATE_LOOKED_UP
        result.trace.precedingDate!!.value shouldBe invoiceDate
        result.trace.ownDate.value shouldBe creditDate
    }

    @Test
    fun `INV-AC-746 only rate and code move to the original's date, the own date stays the credit note's`() {
        // scope, MDE, Schematron, period and due date are resolved by the caller at the own date, which the trace keeps
        val result = creditNote(creditDate, "INV-100", invoiceDate).coded()
        result.trace.ownDate.value shouldBe creditDate
        PackTypes.RESOLVED_AT_PRECEDING_DATE shouldBe setOf(PackTypes.CODE_CATALOGUE)
        ResolutionDates(TransactionDate(creditDate), TransactionDate(invoiceDate))
            .forPack(PackTypes.SCOPE)
            .value shouldBe creditDate
    }

    @Test
    fun `INV-AC-747 a blank-linked credit note after the change resolves by its own date and notes the straddle`() {
        val result = creditNote(creditDate).coded()
        result.document.lines
            .single()
            .rate
            .compareTo(BigDecimal("10")) shouldBe 0
        result.document.lines
            .single()
            .categoryCode shouldBe "SR"
        result.trace.basis shouldBe RateDateBasis.OWN_DATE_BLANK_LINKED
        result.trace.precedingDate shouldBe null
        result.trace.rateDate.value shouldBe creditDate
        result.trace.justificationNote!! shouldContain "straddle"
        result.trace.justificationNote shouldContain creditDate.toString()
    }

    @Test
    fun `INV-AC-747 a blank-linked credit note before the change resolves by its own date too`() {
        val result = creditNote(before).coded()
        result.document.lines
            .single()
            .rate
            .compareTo(BigDecimal("9")) shouldBe 0
        result.trace.rateDate.value shouldBe before
        result.trace.justificationNote!! shouldContain "straddle"
    }

    @Test
    fun `INV-AC-747 a blank number is treated as no preceding document, not as an unknown original`() {
        creditNote(creditDate, "  ").coded().trace.basis shouldBe RateDateBasis.OWN_DATE_BLANK_LINKED
    }

    @Test
    fun `INV-AC-1819 a credit note naming an unknown original without its date raises RUL-008 and takes no rate`() {
        val result = creditNote(creditDate, "INV-NEVER").shouldBeInstanceOf<CreditNoteCodingResult.UnknownOriginal>()
        result.code shouldBe ErrorCode.RUL_008
        result.precedingDocumentNumber shouldBe "INV-NEVER"
    }

    @Test
    fun `INV-AC-1819 a stated preceding date needs no lookup even when the number is unknown`() {
        creditNote(creditDate, "INV-NEVER", invoiceDate)
            .coded()
            .trace.rateDate.value shouldBe invoiceDate
    }
}
