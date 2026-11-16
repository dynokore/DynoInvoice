package com.dynokore.gst.aggregation.domain

import java.math.BigDecimal

/** One line of an aggregate: an internal tax code and its amount as a decimal string (API-12). */
data class AggregateLine(
    val internalTaxCode: String,
    val amount: String,
    /**
     * PCP only (INV-REQ-437): the tax-exclusive amount on which the claimable input tax is claimed; stated only when
     * every line merged into this one states it, never a partial sum.
     */
    val taxableAmount: String? = null,
)

/** Line arithmetic of an aggregate (INV-REQ-427 item 3: one line per distinct code). Amounts are exact decimals. */
object AggregateLines {
    /**
     * One line per distinct code, in order of first appearance, amounts of the same code added; the taxable amount is
     * the exact sum when every line of the code states one, otherwise null.
     */
    fun merge(lines: List<AggregateLine>): List<AggregateLine> =
        lines
            .groupBy { it.internalTaxCode }
            .map { (code, same) ->
                AggregateLine(
                    code,
                    same.fold(BigDecimal.ZERO) { sum, line -> sum + BigDecimal(line.amount) }.toPlainString(),
                    same
                        .map { it.taxableAmount }
                        .takeIf { all -> all.all { it != null } }
                        ?.fold(BigDecimal.ZERO) { sum, value -> sum + BigDecimal(value) }
                        ?.toPlainString(),
                )
            }

    /**
     * The per-code difference `revision - transmitted` (INV-REQ-434 item 4, INV-REQ-439): a code only in [transmitted]
     * gives its negated amount. The taxable difference is stated only when both merged lines state one. A code is left
     * out only when its amount difference is zero and its taxable difference is null or zero.
     */
    fun difference(
        revision: List<AggregateLine>,
        transmitted: List<AggregateLine>,
    ): List<AggregateLine> {
        val sent = merge(transmitted).associateBy { it.internalTaxCode }
        val now = merge(revision).associateBy { it.internalTaxCode }
        return (now.keys + sent.keys).mapNotNull { code ->
            val amount = BigDecimal(now[code]?.amount ?: "0") - BigDecimal(sent[code]?.amount ?: "0")
            val nowTaxable = now[code]?.taxableAmount
            val sentTaxable = sent[code]?.taxableAmount
            val taxable =
                if (nowTaxable != null &&
                    sentTaxable != null
                ) {
                    BigDecimal(nowTaxable) - BigDecimal(sentTaxable)
                } else {
                    null
                }
            if (amount.signum() == 0 && (taxable == null || taxable.signum() == 0)) {
                null
            } else {
                AggregateLine(code, amount.toPlainString(), taxable?.toPlainString())
            }
        }
    }
}
