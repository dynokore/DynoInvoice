package com.dynokore.gst.masterdata.domain.rate

import java.math.BigDecimal
import java.time.LocalDate

/** How a rate record was loaded (INV-REQ-506); only manual entry is loaded by this slice. */
enum class Provenance { MANUAL_ENTRY, }

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
    val evidenceRef: String,
    val revision: Int,
) {
    init {
        require(source.isNotBlank()) { "a source is required" }
        require(CURRENCY.matches(baseCurrency) && CURRENCY.matches(quoteCurrency)) { "a currency is 3 capital letters" }
        require(rate.signum() > 0) { "a rate is positive" }
        require(evidenceRef.isNotBlank()) { "a manual rate needs an evidence reference" }
        require(revision >= 1) { "a revision starts at 1" }
    }

    private companion object {
        val CURRENCY = Regex("^[A-Z]{3}$")
    }
}
