package com.dynokore.gst.aggregation.domain

/**
 * ING-006 (error-codes.yaml: BLOCK, TERMINAL; INV-REQ-410): a transaction outside POS, STI and PCP offered to
 * aggregation. It is a specific code rather than a generic validation failure (INV-REQ-812) and has no override
 * path: [overridable] is a constant `false` that no caller can change.
 */
data class NotAggregatable(
    val offered: String?,
) {
    val code: String get() = CODE
    val terminal: Boolean get() = true
    val overridable: Boolean get() = false

    companion object {
        const val CODE = "ING-006"
    }
}
