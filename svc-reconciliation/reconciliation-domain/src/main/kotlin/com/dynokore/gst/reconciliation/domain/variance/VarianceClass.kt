package com.dynokore.gst.reconciliation.domain.variance

/**
 * The variance class vocabulary (INV-Q-7237 A): UPPER_SNAKE_CASE members of a vocabulary, not field mappings
 * (INV-Q-6109), so [value] is the member name and the contract value. The order is the order of the categories of
 * INV-BR-1710, then the rejected-by-the-buyer addition, then the absence of a category.
 */
enum class VarianceClass {
    TIMING_DIFFERENCE,
    NO_INVOICE_REQUIRED,
    INPUT_TAX_NOT_CLAIMED,
    ERROR_CORRECTED_VIA_F5,
    EXCLUDED_TXN,
    PRE_OBLIGATION,
    OUTSIDE_BY_RULE,
    BUYER_REJECTED,
    UNEXPLAINED,
    ;

    val value: String get() = name
}
