package com.dynokore.gst.ingestion.domain.completeness

import java.math.BigDecimal

data class TaxCodeTotal(
    val taxCode: String,
    val amount: BigDecimal,
)

data class PeriodTotals(
    val count: Long,
    val totalsByTaxCode: List<TaxCodeTotal>,
)

data class CountDifference(
    val upstream: Long,
    val ingested: Long,
)

data class TaxCodeDifference(
    val taxCode: String,
    val upstream: BigDecimal,
    val ingested: BigDecimal,
)

sealed interface ControlTotalsVerdict {
    data object Agrees : ControlTotalsVerdict

    data class Disagrees(
        val code: String,
        val count: CountDifference?,
        val values: List<TaxCodeDifference>,
    ) : ControlTotalsVerdict
}

/** Count and value agreement of the completeness controls (INV-REQ-791, INV-AC-1847); exact, no tolerance. */
object ControlTotalsAgreement {
    const val PER_002 = "PER-002"

    fun compare(
        upstream: PeriodTotals,
        ingested: PeriodTotals,
    ): ControlTotalsVerdict {
        val upstreamByCode = validated(upstream)
        val ingestedByCode = validated(ingested)
        val count =
            if (upstream.count != ingested.count) CountDifference(upstream.count, ingested.count) else null
        val values =
            (upstreamByCode.keys + ingestedByCode.keys).sorted().mapNotNull { code ->
                val up = upstreamByCode[code] ?: BigDecimal.ZERO
                val ing = ingestedByCode[code] ?: BigDecimal.ZERO
                if (up.compareTo(ing) != 0) TaxCodeDifference(code, up, ing) else null
            }
        return if (count == null && values.isEmpty()) {
            ControlTotalsVerdict.Agrees
        } else {
            ControlTotalsVerdict.Disagrees(PER_002, count, values)
        }
    }

    private fun validated(totals: PeriodTotals): Map<String, BigDecimal> {
        require(totals.count >= 0) { "count must not be negative: ${totals.count}" }
        val byCode = LinkedHashMap<String, BigDecimal>()
        for (line in totals.totalsByTaxCode) {
            require(line.taxCode.isNotBlank()) { "taxCode must not be blank" }
            require(byCode.put(line.taxCode, line.amount) == null) { "duplicate taxCode: ${line.taxCode}" }
        }
        return byCode
    }
}
