package com.dynokore.gst.aggregation.domain

import java.math.BigDecimal

/** One line of an aggregate: an internal tax code and its amount as a decimal string (API-12). */
data class AggregateLine(
    val internalTaxCode: String,
    val amount: String,
)

/** Line arithmetic of an aggregate (INV-REQ-427 item 3: one line per distinct code). Amounts are exact decimals. */
object AggregateLines {
    /** One line per distinct code, in order of first appearance, amounts of the same code added. */
    fun merge(lines: List<AggregateLine>): List<AggregateLine> =
        lines
            .groupBy { it.internalTaxCode }
            .map { (code, same) ->
                AggregateLine(
                    code,
                    same
                        .fold(BigDecimal.ZERO) { sum, line ->
                            sum +
                                BigDecimal(line.amount)
                        }.toPlainString(),
                )
            }

    /**
     * The per-code difference `revision - transmitted` (INV-REQ-434 item 4, INV-REQ-439): a code whose difference is
     * zero is left out; a code only in [transmitted] gives its negated amount.
     */
    fun difference(
        revision: List<AggregateLine>,
        transmitted: List<AggregateLine>,
    ): List<AggregateLine> {
        val sent = merge(transmitted).associate { it.internalTaxCode to BigDecimal(it.amount) }
        val now = merge(revision).associate { it.internalTaxCode to BigDecimal(it.amount) }
        return (now.keys + sent.keys)
            .map { it to (now[it] ?: BigDecimal.ZERO) - (sent[it] ?: BigDecimal.ZERO) }
            .filter { it.second.signum() != 0 }
            .map { (code, amount) -> AggregateLine(code, amount.toPlainString()) }
    }
}
