package com.dynokore.gst.masterdata.domain.rate

import java.math.BigDecimal
import java.time.LocalDate

/** How a rate record was loaded (INV-REQ-506): manual entry with evidence, or import with its import id. */
enum class Provenance { MANUAL_ENTRY, IMPORT }

/**
 * INV-REQ-506: one effective-dated rate record per [source], currency pair and [date]. A repeat supersedes: the new
 * record has the next [revision] and the earlier one stays readable.
 */
data class ExchangeRate(
    val source: String,
    val baseCurrency: String,
    val quoteCurrency: String,
    val date: LocalDate,
    val rate: BigDecimal,
    val provenance: Provenance,
    val evidenceRef: String?,
    val revision: Int,
    val importId: String? = null,
) {
    init {
        require(source.isNotBlank()) { "a source is required" }
        require(CURRENCY.matches(baseCurrency) && CURRENCY.matches(quoteCurrency)) { "a currency is 3 capital letters" }
        require(rate.signum() > 0) { "a rate is positive" }
        when (provenance) {
            Provenance.MANUAL_ENTRY -> {
                require(!evidenceRef.isNullOrBlank()) { "a manual rate needs an evidence reference" }
                require(importId == null) { "a manual rate has no import identifier" }
            }

            Provenance.IMPORT -> {
                require(!importId.isNullOrBlank()) { "an imported rate needs an import identifier" }
                require(evidenceRef == null) { "an imported rate has no manual evidence" }
            }
        }
        require(revision >= 1) { "a revision starts at 1" }
    }

    private companion object {
        val CURRENCY = Regex("^[A-Z]{3}$")
    }
}
