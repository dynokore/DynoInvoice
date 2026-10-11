package com.dynokore.platform.serviceruntime.staleness

import com.dynokore.platform.outboxandqueue.StalenessBreach
import com.dynokore.platform.outboxandqueue.StalenessBreachSink
import org.slf4j.LoggerFactory

/**
 * The default [StalenessBreachSink]: a WARN log line naming consumer, event type, lag and threshold. The incident
 * itself is raised by the observability bundle's alert rule on the lag gauge (coordinator ruling INV-Q-3030 (a)), so
 * a service needs no sink of its own; one it declares replaces this.
 */
class LoggingStalenessBreachSink : StalenessBreachSink {
    override fun raise(breach: StalenessBreach) {
        log.warn(
            "consumer staleness breach: consumer={} event_type={} lag={} threshold={}",
            breach.consumer,
            breach.eventType,
            breach.lag,
            breach.threshold,
        )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(LoggingStalenessBreachSink::class.java)
    }
}
