package com.dynokore.platform.rulesevaluation.classification

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackType
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import com.dynokore.platform.rulepackkernel.evaluation.AppliedPack
import com.dynokore.platform.rulesevaluation.resolution.TransactionDate
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/** Classification over a fixture scope-pack table. Every kind, code and amount is invented test data. */
class ClassifyDocumentTest {
    private val applied = AppliedPack(PackType("SCOPE"), PackId("scope-1"), PackVersionLabel("1"))

    @Suppress("LongParameterList") // a fixture row: the seven cells of one table row
    private fun row(
        upstream: DocumentFunction,
        kind: String,
        sign: SignOfTotal,
        result: DocumentFunction,
        code: String?,
        normalised: Boolean = false,
        cancellation: Boolean = false,
    ) = ClassificationRow(upstream, kind, sign, result, code, normalised, false, null, cancellation)

    private val bill = DocumentFunction.BILL_FOR_PAYMENT
    private val adj = DocumentFunction.ADJUSTMENT
    private val notBill = DocumentFunction.NOT_A_BILL

    private val table =
        ClassificationTable(
            listOf(
                row(bill, "INVOICE", SignOfTotal.POSITIVE, bill, "380"),
                row(bill, "INVOICE", SignOfTotal.NEGATIVE, adj, "381", normalised = true),
                row(bill, "POS_RECEIPT", SignOfTotal.POSITIVE, bill, "380"),
                row(bill, "PRO_FORMA", SignOfTotal.POSITIVE, notBill, null),
                row(notBill, "ADVANCE_PAYMENT", SignOfTotal.POSITIVE, notBill, null),
                row(adj, "DEBIT_NOTE", SignOfTotal.POSITIVE, adj, "380"),
                row(adj, "CREDIT_NOTE", SignOfTotal.POSITIVE, adj, "381"),
                row(adj, "CANCELLATION", SignOfTotal.POSITIVE, adj, "381", cancellation = true),
            ),
            applied,
        )

    /** The moved classifier takes the table and the input directly; the svc-rules port stays there. */
    private fun result(
        input: ClassifyInput,
        on: ClassificationTable = table,
    ): ClassifyResult =
        DocumentClassifier.classify(input, on)?.let { ClassifyResult.Classified(it) }
            ?: ClassifyResult.NoClassificationRow(
                input.upstreamFunction,
                input.documentKind,
                DocumentClassifier.signOfTotal(input),
                on.applied,
            )

    private sealed interface ClassifyResult {
        data class Classified(
            val classification: Classification,
        ) : ClassifyResult

        data class NoClassificationRow(
            val upstreamFunction: DocumentFunction,
            val documentKind: String,
            val signOfTotal: SignOfTotal,
            val applied: AppliedPack?,
        ) : ClassifyResult
    }

    private fun journal(posting: JournalPosting) = DocumentClassifier.classifyJournal(posting)

    private fun lines(vararg amounts: String) =
        amounts.mapIndexed { i, a -> ClassifiedLine("L${i + 1}", BigDecimal(a)) }

    private fun classify(
        asserted: DocumentFunction,
        kind: String,
        vararg amounts: String,
        prevNumber: String? = null,
        prevDate: TransactionDate? = null,
    ): Classification =
        result(ClassifyInput(asserted, kind, lines(*amounts), prevNumber, prevDate))
            .shouldBeInstanceOf<ClassifyResult.Classified>()
            .classification

    @Test
    fun `INV-AC-1648 a serially numbered POS receipt in lieu of a tax invoice is invoice data`() {
        val c =
            classify(
                bill,
                "POS_RECEIPT",
                "10.00",
            ).shouldBeInstanceOf<Classification.InvoiceData>()
        c.documentFunction shouldBe bill
        c.documentTypeCode shouldBe "380"
    }

    @Test
    fun `INV-AC-95 classification part - the receipt is classified by function, not by its upstream name`() {
        // an asserted INVOICE of kind POS_RECEIPT is invoice data: the kind keys the table, the name decides nothing
        classify(bill, "POS_RECEIPT", "10.00").shouldBeInstanceOf<Classification.InvoiceData>()
    }

    @Test
    fun `INV-AC-1649 a pro-forma asserted as a bill for payment is reclassified NOT_A_BILL and not submitted`() {
        val c = classify(bill, "PRO_FORMA", "10.00")
        val notABill = c.shouldBeInstanceOf<Classification.NotABill>()
        notABill.trace!!.documentFunction shouldBe notBill
        notABill.trace.documentTypeCode shouldBe null
    }

    @Test
    fun `INV-AC-201 classification part - an advance-payment record is NOT_A_BILL and no submission trigger`() {
        classify(notBill, "ADVANCE_PAYMENT", "10.00").shouldBeInstanceOf<Classification.NotABill>()
        // even when no row names the kind, an asserted NOT_A_BILL is kept and never submitted
        classify(notBill, "REMITTANCE", "10.00").shouldBeInstanceOf<Classification.NotABill>()
    }

    @Test
    fun `INV-AC-385 a journal-only supply is invoice data dated by its posting date`() {
        val posting = TransactionDate(LocalDate.of(2026, 7, 31))
        val c =
            journal(JournalPosting(posting, "TC-1"))
                .shouldBeInstanceOf<JournalClassification.Supply>()
        c.transactionDate shouldBe posting
        c.documentFunction shouldBe bill
        journal(JournalPosting(posting, null)) shouldBe JournalClassification.NotASupply
        journal(JournalPosting(posting, " ")) shouldBe JournalClassification.NotASupply
    }

    @Test
    fun `INV-AC-401 a negative-total invoice is normalised to 381 and the mapping is traced`() {
        val c =
            classify(
                bill,
                "INVOICE",
                "-100.00",
                "-5.00",
            ).shouldBeInstanceOf<Classification.InvoiceData>()
        c.documentTypeCode shouldBe "381"
        c.signNormalised shouldBe true
        c.trace.tableKey shouldBe "BILL_FOR_PAYMENT/INVOICE/NEGATIVE"
    }

    @Test
    fun `INV-AC-1717 each adjustment is typed by the table and the application is in its trace`() {
        val c = classify(bill, "INVOICE", "-1.00").shouldBeInstanceOf<Classification.InvoiceData>()
        c.trace.ruleId shouldBe "FR-AE.61"
        c.trace.applied shouldBe applied
        c.trace.signOfTotal shouldBe SignOfTotal.NEGATIVE
        c.trace.documentTypeCode shouldBe "381"
        c.trace.signNormalised shouldBe true
        c.trace.upstreamFunction shouldBe bill
    }

    @Test
    fun `INV-AC-402 a debit note maps to 380 with the preceding-document reference`() {
        val prev = TransactionDate(LocalDate.of(2026, 7, 1))
        val c =
            classify(adj, "DEBIT_NOTE", "50.00", prevNumber = "INV-9", prevDate = prev)
                .shouldBeInstanceOf<Classification.InvoiceData>()
        c.documentTypeCode shouldBe "380"
        c.precedingDocumentNumber shouldBe "INV-9"
        c.precedingDocumentDate shouldBe prev
        c.debitNote shouldBe true
        c.submissionMethod shouldBe SubmissionMethod.SOLUTION_EXTRACTED_ONLY
    }

    @Test
    fun `INV-AC-1558 verdict part - only a debit note is flagged solution-extracted`() {
        val credit =
            classify(
                adj,
                "CREDIT_NOTE",
                "50.00",
            ).shouldBeInstanceOf<Classification.InvoiceData>()
        credit.debitNote shouldBe false
        credit.submissionMethod shouldBe SubmissionMethod.NETWORK_OR_EXTRACTED
        val plain =
            classify(
                bill,
                "INVOICE",
                "50.00",
            ).shouldBeInstanceOf<Classification.InvoiceData>()
        plain.submissionMethod shouldBe SubmissionMethod.NETWORK_OR_EXTRACTED
    }

    @Test
    fun `INV-AC-1651 a cancellation maps to 381, is traced and is checked against its original`() {
        val c =
            classify(
                adj,
                "CANCELLATION",
                "100.00",
            ).shouldBeInstanceOf<Classification.InvoiceData>()
        c.documentTypeCode shouldBe "381"
        c.cancellation shouldBe true
        c.checkAgainstOriginal shouldBe true
        c.trace.tableKey shouldBe "ADJUSTMENT/CANCELLATION/POSITIVE"
        classify(adj, "CREDIT_NOTE", "1.00")
            .shouldBeInstanceOf<Classification.InvoiceData>()
            .checkAgainstOriginal shouldBe false
    }

    @Test
    fun `INV-AC-403 an adjustment with mixed-sign lines is held with ING-015 and not split`() {
        val c = classify(adj, "CREDIT_NOTE", "10.00", "-4.00", "0.00")
        val held = c.shouldBeInstanceOf<Classification.HeldMixedSign>()
        held.code shouldBe ErrorCode.ING_015
        held.positiveLines shouldBe listOf("L1")
        held.negativeLines shouldBe listOf("L2")
    }

    @Test
    fun `INV-AC-1718 a mixed-sign document is held, no table row is applied and nothing is submitted`() {
        // an empty table proves the hold comes before any table use; a ZERO line adds no sign
        val noTable = ClassificationTable(emptyList(), applied)
        result(ClassifyInput(bill, "INVOICE", lines("5", "-5")), noTable)
            .shouldBeInstanceOf<ClassifyResult.Classified>()
            .classification
            .shouldBeInstanceOf<Classification.HeldMixedSign>()
        classify(bill, "INVOICE", "5", "0").shouldBeInstanceOf<Classification.InvoiceData>()
    }

    private fun noRow(
        function: DocumentFunction,
        kind: String,
        amount: String,
    ) = result(ClassifyInput(function, kind, lines(amount)))
        .shouldBeInstanceOf<ClassifyResult.NoClassificationRow>()

    @Test
    fun `a key with no row is NoClassificationRow with the input key and the table pack, never a type code`() {
        // look-alike kinds, another upstream function and another sign: exact equality on all three parts
        listOf("invoice", "INVOICE ", "INVOICE2").forEach {
            val r = noRow(bill, it, "1.00")
            r.upstreamFunction shouldBe bill
            r.documentKind shouldBe it
            r.signOfTotal shouldBe SignOfTotal.POSITIVE
            r.applied shouldBe applied
        }
        noRow(adj, "INVOICE", "1.00").upstreamFunction shouldBe adj
        noRow(bill, "POS_RECEIPT", "-1.00").signOfTotal shouldBe SignOfTotal.NEGATIVE
        noRow(bill, "INVOICE", "0.00").signOfTotal shouldBe SignOfTotal.ZERO
    }

    @Test
    fun `an asserted NOT_A_BILL no row contradicts is kept without a trace entry`() {
        classify(notBill, "UNLISTED", "1.00").shouldBeInstanceOf<Classification.NotABill>().trace shouldBe null
    }

    @Test
    fun `a key that matches a row is classified by that row only`() {
        classify(bill, "INVOICE", "1.00").shouldBeInstanceOf<Classification.InvoiceData>().trace.tableKey shouldBe
            "BILL_FOR_PAYMENT/INVOICE/POSITIVE"
    }

    @Test
    fun `DocumentFunction members equal the rules contract vocabulary in order`() {
        DocumentFunction.entries.map { it.name } shouldBe listOf("BILL_FOR_PAYMENT", "ADJUSTMENT", "NOT_A_BILL")
    }

    @Test
    fun `ofSign amounts are 1, -1 and 0 and classify as the amounts 100, -100 and 0`() {
        ClassifiedLine.ofSign("L", SignOfTotal.POSITIVE).amount shouldBe BigDecimal.ONE
        ClassifiedLine.ofSign("L", SignOfTotal.NEGATIVE).amount shouldBe BigDecimal.ONE.negate()
        ClassifiedLine.ofSign("L", SignOfTotal.ZERO).amount.signum() shouldBe 0
        listOf(
            SignOfTotal.POSITIVE to "100.00",
            SignOfTotal.NEGATIVE to "-100.00",
            SignOfTotal.ZERO to "0.00",
        ).forEach { (sign, amount) ->
            fun of(line: ClassifiedLine) = result(ClassifyInput(bill, "INVOICE", listOf(line)))
            val viaSign = of(ClassifiedLine.ofSign("L1", sign))
            viaSign shouldBe of(ClassifiedLine("L1", BigDecimal(amount)))
        }
        // the negative ofSign line gives a negative total, so 381
        classify(bill, "INVOICE", "-1").shouldBeInstanceOf<Classification.InvoiceData>().documentTypeCode shouldBe "381"
        val held =
            result(
                ClassifyInput(
                    adj,
                    "CREDIT_NOTE",
                    listOf(
                        ClassifiedLine.ofSign("A", SignOfTotal.POSITIVE),
                        ClassifiedLine.ofSign("B", SignOfTotal.NEGATIVE),
                    ),
                ),
            )
        held
            .shouldBeInstanceOf<ClassifyResult.Classified>()
            .classification
            .shouldBeInstanceOf<Classification.HeldMixedSign>()
    }

    private fun debit(
        number: String?,
        prevDate: TransactionDate?,
    ) = classify(adj, "DEBIT_NOTE", "50.00", prevNumber = number, prevDate = prevDate)
        .shouldBeInstanceOf<Classification.InvoiceData>()

    @Test
    fun `a debit note passes preceding-document fields through as stated - both present, one present, both null`() {
        val d = TransactionDate(LocalDate.of(2026, 7, 1))
        debit("INV-9", d).let {
            it.precedingDocumentNumber shouldBe "INV-9"
            it.precedingDocumentDate shouldBe d
        }
        debit("INV-9", null).let {
            it.precedingDocumentNumber shouldBe "INV-9"
            it.precedingDocumentDate shouldBe null
        }
        debit(null, d).let {
            it.precedingDocumentNumber shouldBe null
            it.precedingDocumentDate shouldBe d
        }
        debit(null, null).let {
            it.precedingDocumentNumber shouldBe null
            it.precedingDocumentDate shouldBe null
            it.documentTypeCode shouldBe "380"
            it.submissionMethod shouldBe SubmissionMethod.SOLUTION_EXTRACTED_ONLY
        }
    }
}
