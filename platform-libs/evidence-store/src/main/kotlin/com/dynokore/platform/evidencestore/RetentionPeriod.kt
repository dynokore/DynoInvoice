package com.dynokore.platform.evidencestore

/**
 * Retention length in whole years (FR-D.3): configurable per tier and
 * jurisdiction, never below the statutory floor of five years — the `init`
 * block is that floor, enforced wherever a [RetentionPeriod] is constructed,
 * not just at one call site (ruling INV-Q-1798: "No value below five years is
 * accepted").
 */
@JvmInline
value class RetentionPeriod(val years: Int) {
    init {
        require(years >= DEFAULT_YEARS) { "retention must be at least $DEFAULT_YEARS years (FR-D.3), was $years" }
    }

    companion object {
        const val DEFAULT_YEARS = 5
        val DEFAULT = RetentionPeriod(DEFAULT_YEARS)
    }
}
