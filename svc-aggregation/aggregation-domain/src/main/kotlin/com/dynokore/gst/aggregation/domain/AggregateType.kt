package com.dynokore.gst.aggregation.domain

/**
 * The only transaction categories that may be aggregated (INV-REQ-410, FR-4.5): POS, STI and PCP. The wire value is
 * matched exactly: `pos`, ` POS` and `POS ` are not a category.
 */
enum class AggregateType {
    POS,
    STI,
    PCP,
    ;

    companion object {
        /** The category named by [wire], or null when [wire] is absent or anything other than the three. */
        fun parse(wire: String?): AggregateType? = entries.firstOrNull { it.name == wire }
    }
}
