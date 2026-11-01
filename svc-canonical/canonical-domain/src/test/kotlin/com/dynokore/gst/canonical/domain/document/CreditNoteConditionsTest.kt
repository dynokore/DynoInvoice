package com.dynokore.gst.canonical.domain.document

import com.dynokore.gst.canonical.domain.netting.DocumentFunction
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CreditNoteConditionsTest {
    private val ing019 = listOf(ConditionViolation("ING-019", 400))
    private val srt001 = listOf(ConditionViolation("SRT-001", 400))

    private fun decide(
        function: DocumentFunction,
        number: Boolean = false,
        date: Boolean = false,
        justification: Boolean = false,
        evidence: Boolean = false,
        netted: Boolean = false,
        gstNotAdjusted: Boolean = false,
    ) = CreditNoteConditions.violations(function, number, date, justification, evidence, netted, gstNotAdjusted)

    @Test
    fun `INV-AC-13667 ADJUSTMENT and CREDIT_NOTE without linkage are refused alike`() {
        decide(DocumentFunction.ADJUSTMENT) shouldBe ing019
        decide(DocumentFunction.CREDIT_NOTE) shouldBe ing019
    }

    @Test
    fun `INV-AC-13667 ADJUSTMENT with a preceding number or with justification and evidence is accepted`() {
        decide(DocumentFunction.ADJUSTMENT, number = true) shouldBe emptyList()
        decide(DocumentFunction.ADJUSTMENT, date = true) shouldBe emptyList()
        decide(DocumentFunction.ADJUSTMENT, justification = true, evidence = true) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-13667 one of justification and evidence alone is still refused`() {
        decide(DocumentFunction.ADJUSTMENT, justification = true) shouldBe ing019
        decide(DocumentFunction.ADJUSTMENT, evidence = true) shouldBe ing019
        decide(DocumentFunction.CREDIT_NOTE, justification = true) shouldBe ing019
    }

    @Test
    fun `INV-AC-13667 nettedCreditNoteKeys with ADJUSTMENT is refused as with CREDIT_NOTE and DEBIT_NOTE`() {
        decide(DocumentFunction.ADJUSTMENT, number = true, netted = true) shouldBe srt001
        decide(DocumentFunction.CREDIT_NOTE, number = true, netted = true) shouldBe srt001
        decide(DocumentFunction.DEBIT_NOTE, netted = true) shouldBe srt001
        decide(DocumentFunction.INVOICE, netted = true) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-13667 gstNotAdjusted is accepted for ADJUSTMENT and CREDIT_NOTE and refused for the others`() {
        decide(DocumentFunction.ADJUSTMENT, number = true, gstNotAdjusted = true) shouldBe emptyList()
        decide(DocumentFunction.CREDIT_NOTE, number = true, gstNotAdjusted = true) shouldBe emptyList()
        for (f in listOf(DocumentFunction.DEBIT_NOTE, DocumentFunction.INVOICE, DocumentFunction.BILL_FOR_PAYMENT)) {
            decide(f, gstNotAdjusted = true) shouldBe srt001
        }
    }

    @Test
    fun `INV-AC-13667 DEBIT_NOTE INVOICE BILL_FOR_PAYMENT and NOT_A_BILL without linkage get no ING-019`() {
        for (f in listOf(
            DocumentFunction.DEBIT_NOTE,
            DocumentFunction.INVOICE,
            DocumentFunction.BILL_FOR_PAYMENT,
            DocumentFunction.NOT_A_BILL,
        )) {
            decide(f) shouldBe emptyList()
        }
    }
}
