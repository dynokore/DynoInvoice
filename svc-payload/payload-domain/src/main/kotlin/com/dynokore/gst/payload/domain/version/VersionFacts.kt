package com.dynokore.gst.payload.domain.version

import java.math.BigDecimal

/** The facts of one canonical version that the override rule needs (INV-REQ-1293, INV-Q-17166 A). */
data class VersionFacts(
    val entityRef: String,
    val totalSgd: BigDecimal?,
) {
    init {
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
    }
}

/** The SGD total including GST of a canonical version; the amount is used as stated, never recomputed (FR-M.7). */
private val AMOUNT = Regex("^-?\\d{1,17}(\\.\\d{1,2})?$")

/**
 * The stated [sgdTotalIncludingTax], else [totalIncludingTax] when [currency] is exactly "SGD", else null.
 * Throws [IllegalArgumentException] for a present text outside the canonical schema pattern (fail closed).
 */
fun totalSgdIncludingTax(
    currency: String?,
    totalIncludingTax: String?,
    sgdTotalIncludingTax: String?,
): BigDecimal? {
    val chosen =
        when {
            sgdTotalIncludingTax != null -> sgdTotalIncludingTax
            currency == "SGD" -> totalIncludingTax
            else -> null
        }
    if (chosen == null) return null
    require(AMOUNT.matches(chosen)) { "amount text does not match the canonical pattern" }
    return BigDecimal(chosen)
}
