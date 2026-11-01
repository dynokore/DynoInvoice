package com.dynokore.gst.rules.application

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.rules.domain.classification.AssertedFunction
import com.dynokore.gst.rules.domain.classification.Classification
import com.dynokore.gst.rules.domain.classification.ClassificationFailure
import com.dynokore.gst.rules.domain.classification.ClassificationRow
import com.dynokore.gst.rules.domain.classification.ClassificationTable
import com.dynokore.gst.rules.domain.classification.ClassifiedLine
import com.dynokore.gst.rules.domain.classification.ClassifyInput
import com.dynokore.gst.rules.domain.classification.DocumentFunction
import com.dynokore.gst.rules.domain.classification.JournalClassification
import com.dynokore.gst.rules.domain.classification.JournalPosting
import com.dynokore.gst.rules.domain.classification.SignOfTotal
import com.dynokore.gst.rules.domain.classification.SubmissionMethod
import com.dynokore.gst.rules.domain.resolution.AppliedPack
import com.dynokore.gst.rules.domain.resolution.PackTypes
import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.rulepackkernel.PackId
import com.dynokore.platform.rulepackkernel.PackVersionLabel
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.LocalDate

/** Classification over a fixture scope-pack table. Every kind, code and amount is invented test data. */
class ClassifyDocumentTest {
    private val date = TransactionDate(LocalDate.of(2026, 8, 3))
    private val applied = AppliedPack(PackTypes.SCOPE, PackId("scope-1"), PackVersionLabel("1"))

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

    private val service = ClassifyDocument { _, _ -> table }

    private fun lines(vararg amounts: String) =
        amounts.mapIndexed { i, a -> ClassifiedLine("L${i + 1}", BigDecimal(a)) }

    private fun classify(
        asserted: AssertedFunction,
        kind: String,
        vararg amounts: String,
        prevNumber: String? = null,
        prevDate: TransactionDate? = null,
    ): Classification =
        service
            .classify(ClassifyCommand(null, date, ClassifyInput(asserted, kind, lines(*amounts), prevNumber, prevDate)))
            .shouldBeInstanceOf<ClassifyResult.Classified>()
            .classification

    @Test
    fun `INV-AC-1648 a serially numbered POS receipt in lieu of a tax invoice is invoice data`() {
        val c =
            classify(
                AssertedFunction.BILL_FOR_PAYMENT,
                "POS_RECEIPT",
                "10.00",
            ).shouldBeInstanceOf<Classification.InvoiceData>()
        c.documentFunction shouldBe bill
        c.documentTypeCode shouldBe "380"
    }

    @Test
    fun `INV-AC-95 classification part - the receipt is classified by function, not by its upstream name`() {
        // an asserted INVOICE of kind POS_RECEIPT is invoice data: the kind keys the table, the name decides nothing
        classify(AssertedFunction.INVOICE, "POS_RECEIPT", "10.00").shouldBeInstanceOf<Classification.InvoiceData>()
    }

    @Test
    fun `INV-AC-1649 a pro-forma asserted as a bill for payment is reclassified NOT_A_BILL and not submitted`() {
        val c = classify(AssertedFunction.BILL_FOR_PAYMENT, "PRO_FORMA", "10.00")
        val notABill = c.shouldBeInstanceOf<Classification.NotABill>()
        notABill.trace!!.documentFunction shouldBe notBill
        notABill.trace!!.documentTypeCode shouldBe null
    }

    @Test
    fun `INV-AC-201 classification part - an advance-payment record is NOT_A_BILL and no submission trigger`() {
        classify(AssertedFunction.NOT_A_BILL, "ADVANCE_PAYMENT", "10.00").shouldBeInstanceOf<Classification.NotABill>()
        // even when no row names the kind, an asserted NOT_A_BILL is kept and never submitted
        classify(AssertedFunction.NOT_A_BILL, "REMITTANCE", "10.00").shouldBeInstanceOf<Classification.NotABill>()
    }

    @Test
    fun `INV-AC-385 a journal-only supply is invoice data dated by its posting date`() {
        val posting = TransactionDate(LocalDate.of(2026, 7, 31))
        val c =
            service
                .classifyJournal(
                    JournalPosting(posting, "TC-1"),
                ).shouldBeInstanceOf<JournalClassification.Supply>()
        c.transactionDate shouldBe posting
        c.documentFunction shouldBe bill
        service.classifyJournal(JournalPosting(posting, null)) shouldBe JournalClassification.NotASupply
        service.classifyJournal(JournalPosting(posting, " ")) shouldBe JournalClassification.NotASupply
    }

    @Test
    fun `INV-AC-401 a negative-total invoice is normalised to 381 and the mapping is traced`() {
        val c =
            classify(
                AssertedFunction.INVOICE,
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
        val c = classify(AssertedFunction.INVOICE, "INVOICE", "-1.00").shouldBeInstanceOf<Classification.InvoiceData>()
        c.trace.ruleId shouldBe "FR-AE.61"
        c.trace.applied shouldBe applied
        c.trace.signOfTotal shouldBe SignOfTotal.NEGATIVE
        c.trace.documentTypeCode shouldBe "381"
        c.trace.signNormalised shouldBe true
        c.trace.asserted shouldBe AssertedFunction.INVOICE
    }

    @Test
    fun `INV-AC-402 a debit note maps to 380 with the preceding-document reference`() {
        val prev = TransactionDate(LocalDate.of(2026, 7, 1))
        val c =
            classify(AssertedFunction.DEBIT_NOTE, "DEBIT_NOTE", "50.00", prevNumber = "INV-9", prevDate = prev)
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
                AssertedFunction.CREDIT_NOTE,
                "CREDIT_NOTE",
                "50.00",
            ).shouldBeInstanceOf<Classification.InvoiceData>()
        credit.debitNote shouldBe false
        credit.submissionMethod shouldBe SubmissionMethod.NETWORK_OR_EXTRACTED
        val plain =
            classify(
                AssertedFunction.INVOICE,
                "INVOICE",
                "50.00",
            ).shouldBeInstanceOf<Classification.InvoiceData>()
        plain.submissionMethod shouldBe SubmissionMethod.NETWORK_OR_EXTRACTED
    }

    @Test
    fun `INV-AC-1651 a cancellation maps to 381, is traced and is checked against its original`() {
        val c =
            classify(
                AssertedFunction.ADJUSTMENT,
                "CANCELLATION",
                "100.00",
            ).shouldBeInstanceOf<Classification.InvoiceData>()
        c.documentTypeCode shouldBe "381"
        c.cancellation shouldBe true
        c.checkAgainstOriginal shouldBe true
        c.trace.tableKey shouldBe "ADJUSTMENT/CANCELLATION/POSITIVE"
        classify(AssertedFunction.ADJUSTMENT, "CREDIT_NOTE", "1.00")
            .shouldBeInstanceOf<Classification.InvoiceData>()
            .checkAgainstOriginal shouldBe false
    }

    @Test
    fun `INV-AC-403 an adjustment with mixed-sign lines is held with ING-015 and not split`() {
        val c = classify(AssertedFunction.ADJUSTMENT, "CREDIT_NOTE", "10.00", "-4.00", "0.00")
        val held = c.shouldBeInstanceOf<Classification.HeldMixedSign>()
        held.code shouldBe ErrorCode.ING_015
        held.positiveLines shouldBe listOf("L1")
        held.negativeLines shouldBe listOf("L2")
    }

    @Test
    fun `INV-AC-1718 a mixed-sign document is held, no table row is applied and nothing is submitted`() {
        // an empty table proves the hold comes before any table use; a ZERO line adds no sign
        val noTable = ClassifyDocument { _, _ -> ClassificationTable(emptyList(), applied) }
        val result =
            noTable.classify(
                ClassifyCommand(null, date, ClassifyInput(AssertedFunction.INVOICE, "INVOICE", lines("5", "-5"))),
            )
        result
            .shouldBeInstanceOf<ClassifyResult.Classified>()
            .classification
            .shouldBeInstanceOf<Classification.HeldMixedSign>()
        classify(AssertedFunction.INVOICE, "INVOICE", "5", "0").shouldBeInstanceOf<Classification.InvoiceData>()
    }

    @Test
    fun `no scope pack in force is RUL-002 and an unsettled key fails closed`() {
        val none = ClassifyDocument { _, _ -> null }
        val refused =
            none
                .classify(ClassifyCommand(null, date, ClassifyInput(AssertedFunction.INVOICE, "INVOICE", lines("1"))))
                .shouldBeInstanceOf<ClassifyResult.Refused>()
        refused.code shouldBe ErrorCode.RUL_002
        refused.packType shouldBe PackTypes.SCOPE
        // look-alike kinds never match: exact equality
        listOf("invoice", "INVOICE ", "INVOICE2").forEach {
            assertThrows<ClassificationFailure> { classify(AssertedFunction.INVOICE, it, "1.00") }
        }
    }
}
