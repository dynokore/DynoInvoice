package com.dynokore.gst.gatewayauthz.staleness

import java.time.Duration

/**
 * The revocation list's staleness bound (owner ruling INV-Q-4021 A): an installation CONTROL setting, default 30 s,
 * that may only be tightened, so a value above the default or not above zero is refused.
 */
@JvmInline
value class StalenessBound(
    val value: Duration,
) {
    init {
        require(!value.isNegative && !value.isZero) { "the staleness bound must be above zero (INV-Q-4021 A)" }
        require(value <= MAX) {
            "the staleness bound may only be tightened: $value is above the default $MAX (INV-Q-4021 A)"
        }
    }

    companion object {
        private val MAX: Duration = Duration.ofSeconds(30)
        val DEFAULT = StalenessBound(MAX)
    }
}
