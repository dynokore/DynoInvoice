package com.dynokore.platform.serviceruntime.staleness

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `service-runtime.staleness.*` (CS-27): the service's staleness rules, one per consumer and event type it consumes
 * (INV-REQ-1165, the INV-NOTE-3 §28.18 event matrix), and how often they are measured.
 *
 * @property rules each rule's consumer, event type (the catalogue's CloudEvents type) and optional threshold. Without
 *   a threshold the rule takes its [ProvisionalStalenessThresholds] value; with one, the value may only be shorter.
 * @property checkInterval how often the monitor measures every rule and refreshes the gauges. A technical setting of
 *   this library, not a threshold: it bounds how late a breach shows on the lag gauge.
 */
@ConfigurationProperties(prefix = StalenessProperties.PREFIX)
data class StalenessProperties(
    val rules: List<Rule> = emptyList(),
    val checkInterval: Duration = DEFAULT_CHECK_INTERVAL,
) {
    init {
        require(!checkInterval.isZero && !checkInterval.isNegative) { "$PREFIX.check-interval must be positive" }
    }

    /** One configured rule; [threshold] null takes the provisional value. */
    data class Rule(
        val consumer: String = "",
        val eventType: String = "",
        val threshold: Duration? = null,
    )

    companion object {
        const val PREFIX = "service-runtime.staleness"
        val DEFAULT_CHECK_INTERVAL: Duration = Duration.ofSeconds(30)
    }
}
