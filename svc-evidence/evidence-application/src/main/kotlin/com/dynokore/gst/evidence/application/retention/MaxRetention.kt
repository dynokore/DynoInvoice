package com.dynokore.gst.evidence.application.retention

import com.dynokore.platform.configresolver.OptionValueMaximum
import com.dynokore.platform.evidencestore.RetentionPeriod
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The installation's longest VP-43 retention as one ISO-8601 duration in years (INV-REQ-3767 b2; INV-AC-6532): the
 * larger of [defaultYears], the catalogue default every tenant without a setting counts at, and the maximum over every
 * stored LONGER value. A fractional maximum is written with up to two decimals (`P7.5Y`). The read must run in a
 * platform transaction ([OptionValueMaximum]); a failed read throws, so no partial figure is ever returned.
 */
class MaxRetention(
    private val maximum: OptionValueMaximum,
    private val defaultYears: Int,
) {
    /** The figure in years, exact. */
    fun years(): BigDecimal {
        val stored = maximum.maximum(RetentionPolicy.KEY, RetentionPolicy.LONGER)
        return listOfNotNull(BigDecimal(defaultYears), stored).max()
    }

    fun duration(): String =
        "P" + years().setScale(DECIMALS, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "Y"

    /**
     * The figure as the whole years the digest consumer locks for: a fractional figure is rounded up to the next whole
     * year, never down (a lock shorter than the longest retention is the unsafe direction; INV-Q-12043).
     */
    fun period(): RetentionPeriod = RetentionPeriod(years().setScale(0, RoundingMode.CEILING).intValueExact())

    private companion object {
        const val DECIMALS = 2
    }
}
