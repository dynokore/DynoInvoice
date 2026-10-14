package com.dynokore.platform.serviceruntime.eventloop

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `service-runtime.event-loops.*` (CS-27): the loops that drive the service's outbox relay, inbox consumers and replay
 * delivery (INV-T-3938, coordinator ruling INV-Q-3935; INV-REQ-1212, INV-REQ-1222).
 *
 * @property enabled switches every loop off (default true), for tests that call `runOnce` and `pollOnce` themselves.
 * @property relayInterval how long a relay loop waits after a call that did no work; default 1 second, within the
 *   'staleness is seconds' that INV-REQ-628 accepts.
 * @property consumerInterval the same for each inbox consumer loop.
 * @property replayInterval the same for each replay delivery loop.
 * Every interval is between [MIN_INTERVAL] and [MAX_INTERVAL]; another value stops start-up.
 */
@ConfigurationProperties(prefix = EventLoopProperties.PREFIX)
data class EventLoopProperties(
    val enabled: Boolean = true,
    val relayInterval: Duration = DEFAULT_INTERVAL,
    val consumerInterval: Duration = DEFAULT_INTERVAL,
    val replayInterval: Duration = DEFAULT_INTERVAL,
) {
    init {
        mapOf(
            "relay-interval" to relayInterval,
            "consumer-interval" to consumerInterval,
            "replay-interval" to replayInterval,
        ).forEach { (key, value) ->
            require(value >= MIN_INTERVAL && value <= MAX_INTERVAL) {
                "$PREFIX.$key must be between $MIN_INTERVAL and $MAX_INTERVAL, was $value"
            }
        }
    }

    companion object {
        const val PREFIX = "service-runtime.event-loops"
        val DEFAULT_INTERVAL: Duration = Duration.ofSeconds(1)
        val MIN_INTERVAL: Duration = Duration.ofMillis(100)
        val MAX_INTERVAL: Duration = Duration.ofSeconds(60)
    }
}
