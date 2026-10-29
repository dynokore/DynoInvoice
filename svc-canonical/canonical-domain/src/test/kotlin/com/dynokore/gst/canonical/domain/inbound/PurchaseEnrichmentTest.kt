package com.dynokore.gst.canonical.domain.inbound

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PurchaseEnrichmentTest {
    private val receipt = ReceivedSupply("SR", BigDecimal("9.00"))
    private val complete = ApPosting("TX", BigDecimal("100.00"), BigDecimal("4.50"))

    private fun awaiting(vararg fields: PurchaseField) = PurchaseEnrichmentDecision.AwaitingPosting(fields.toList())

    @Test
    fun `INV-AC-12923 case 1 purchase fields come from the posting only`() {
        val result = PurchaseEnrichment.decide(receipt, complete)
        result shouldBe
            PurchaseEnrichmentDecision.Prepared("TX", BigDecimal("100.00"), BigDecimal("4.50"), "SR")
        val prepared = result as PurchaseEnrichmentDecision.Prepared
        listOf(prepared.purchaseCategoryCode, prepared.taxableAmount, prepared.inputTaxClaimed)
            .none { it == "SR" || it == BigDecimal("9.00") } shouldBe true
    }

    @Test
    fun `INV-AC-12923 case 2 posting without purchase category code awaits`() {
        PurchaseEnrichment.decide(receipt, complete.copy(purchaseCategoryCode = null)) shouldBe
            awaiting(PurchaseField.PURCHASE_CATEGORY_CODE)
    }

    @Test
    fun `INV-AC-12923 case 3 no posting awaits all three fields`() {
        PurchaseEnrichment.decide(receipt, null) shouldBe
            awaiting(
                PurchaseField.PURCHASE_CATEGORY_CODE,
                PurchaseField.TAXABLE_AMOUNT,
                PurchaseField.INPUT_TAX_CLAIMED,
            )
    }

    @Test
    fun `INV-AC-12923 case 4 zero input tax claimed is supplied`() {
        PurchaseEnrichment.decide(receipt, complete.copy(inputTaxClaimed = BigDecimal("0.00"))) shouldBe
            PurchaseEnrichmentDecision.Prepared("TX", BigDecimal("100.00"), BigDecimal("0.00"), "SR")
    }

    @Test
    fun `blank purchase category code is missing`() {
        PurchaseEnrichment.decide(receipt, complete.copy(purchaseCategoryCode = " ")) shouldBe
            awaiting(PurchaseField.PURCHASE_CATEGORY_CODE)
    }

    @Test
    fun `missing taxable amount alone is named`() {
        PurchaseEnrichment.decide(receipt, complete.copy(taxableAmount = null)) shouldBe
            awaiting(PurchaseField.TAXABLE_AMOUNT)
    }

    @Test
    fun `missing input tax claimed alone is named`() {
        PurchaseEnrichment.decide(receipt, complete.copy(inputTaxClaimed = null)) shouldBe
            awaiting(PurchaseField.INPUT_TAX_CLAIMED)
    }

    @Test
    fun `two missing fields are listed in declaration order`() {
        PurchaseEnrichment.decide(receipt, ApPosting(null, BigDecimal("1.00"), null)) shouldBe
            awaiting(PurchaseField.PURCHASE_CATEGORY_CODE, PurchaseField.INPUT_TAX_CLAIMED)
    }

    @Test
    fun `supply code equal to posting code still comes from the posting`() {
        val result =
            PurchaseEnrichment.decide(ReceivedSupply("TX", null), complete) as PurchaseEnrichmentDecision.Prepared
        result.purchaseCategoryCode shouldBe "TX"
        result.receivedSupplyCategoryCode shouldBe "TX"
    }

    @Test
    fun `null receipt with complete posting is prepared with no received code`() {
        PurchaseEnrichment.decide(null, complete) shouldBe
            PurchaseEnrichmentDecision.Prepared("TX", BigDecimal("100.00"), BigDecimal("4.50"), null)
    }

    @Test
    fun `amounts are the posting's own objects`() {
        val result = PurchaseEnrichment.decide(receipt, complete) as PurchaseEnrichmentDecision.Prepared
        result.taxableAmount shouldBeSameInstanceAs complete.taxableAmount
        result.inputTaxClaimed shouldBeSameInstanceAs complete.inputTaxClaimed
        result.taxableAmount.scale() shouldBe 2
    }
}
