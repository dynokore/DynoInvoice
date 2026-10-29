package com.dynokore.gst.canonical.domain.inbound

import java.math.BigDecimal

/** The supply-side values of the received document; kept as received, never copied into purchase fields. */
data class ReceivedSupply(
    val supplyCategoryCode: String?,
    val taxAmount: BigDecimal?,
)

/** The buyer's AP posting values; the only source of the purchase fields. */
data class ApPosting(
    val purchaseCategoryCode: String?,
    val taxableAmount: BigDecimal?,
    val inputTaxClaimed: BigDecimal?,
)

enum class PurchaseField { PURCHASE_CATEGORY_CODE, TAXABLE_AMOUNT, INPUT_TAX_CLAIMED }

sealed interface PurchaseEnrichmentDecision {
    data class Prepared(
        val purchaseCategoryCode: String,
        val taxableAmount: BigDecimal,
        val inputTaxClaimed: BigDecimal,
        val receivedSupplyCategoryCode: String?,
    ) : PurchaseEnrichmentDecision

    data class AwaitingPosting(
        val missing: List<PurchaseField>,
    ) : PurchaseEnrichmentDecision
}

/** INV-REQ-433: purchase fields come from the buyer's AP posting only; a missing value is never defaulted. */
object PurchaseEnrichment {
    fun decide(
        receipt: ReceivedSupply?,
        posting: ApPosting?,
    ): PurchaseEnrichmentDecision {
        val code = posting?.purchaseCategoryCode?.takeIf { it.isNotBlank() }
        val taxable = posting?.taxableAmount
        val claimed = posting?.inputTaxClaimed
        val missing =
            buildList {
                if (code == null) add(PurchaseField.PURCHASE_CATEGORY_CODE)
                if (taxable == null) add(PurchaseField.TAXABLE_AMOUNT)
                if (claimed == null) add(PurchaseField.INPUT_TAX_CLAIMED)
            }
        if (code == null || taxable == null || claimed == null) {
            return PurchaseEnrichmentDecision.AwaitingPosting(missing)
        }
        return PurchaseEnrichmentDecision.Prepared(code, taxable, claimed, receipt?.supplyCategoryCode)
    }
}
