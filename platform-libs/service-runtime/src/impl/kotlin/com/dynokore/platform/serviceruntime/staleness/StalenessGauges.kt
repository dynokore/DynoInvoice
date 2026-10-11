package com.dynokore.platform.serviceruntime.staleness

import com.dynokore.platform.outboxandqueue.ConsumerStaleness
import com.dynokore.platform.outboxandqueue.StalenessMonitor
import com.dynokore.platform.outboxandqueue.StalenessRule
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Exports the [StalenessMonitor]'s measurements as two gauges per (consumer, event type) (INV-REQ-1165 b3, coordinator
 * ruling INV-Q-3030 (a)), through the service's Prometheus endpoint (STK-33): [LAG], the age of the oldest event the
 * consumer has not consumed (zero with nothing unconsumed), and [THRESHOLD], the rule's configured threshold. Both
 * carry the bounded labels `consumer` and `event_type` only (CS-36). The observability bundle's alert rules
 * (INV-T-3035) raise the incident when the lag passes the threshold, and when the lag gauge stops being reported.
 *
 * [refresh] measures every rule. When a measurement fails, the lag gauges are withdrawn until the next success, so a
 * monitor that cannot measure reads as absent, never as a lag of zero.
 */
class StalenessGauges(
    private val monitor: StalenessMonitor,
    rules: List<StalenessRule>,
    private val registry: MeterRegistry,
) {
    private data class Key(
        val consumer: String,
        val eventType: String,
    )

    private val lagSeconds = ConcurrentHashMap<Key, Double>()
    private val lagGauges = ConcurrentHashMap<Key, Gauge>()
    private val thresholds = ConcurrentHashMap<Key, Double>()

    init {
        rules.forEach { track(Key(it.consumer, it.eventType), it.threshold) }
    }

    /** Measures every rule now and updates the gauges; returns the measurements, or null when measuring failed. */
    @Suppress("TooGenericExceptionCaught")
    fun refresh(): List<ConsumerStaleness>? {
        val measured =
            try {
                monitor.check()
            } catch (e: Exception) {
                log.error("consumer staleness not measured, lag gauges withdrawn: {}", e.javaClass.simpleName)
                lagGauges.values.forEach(registry::remove)
                lagGauges.clear()
                lagSeconds.clear()
                return null
            }
        measured.forEach {
            val key = Key(it.consumer, it.eventType)
            lagSeconds[key] = seconds(it.lag)
            track(key, it.threshold)
        }
        return measured
    }

    private fun track(
        key: Key,
        threshold: Duration,
    ) {
        if (thresholds.put(key, seconds(threshold)) == null) {
            Gauge
                .builder(THRESHOLD, thresholds) { it[key] ?: Double.NaN }
                .description("Configured staleness threshold of the consumer for the event type (provisional until SDS §13.7)")
                .baseUnit(SECONDS)
                .tags(CONSUMER, key.consumer, EVENT_TYPE, key.eventType)
                .strongReference(true)
                .register(registry)
        }
        lagGauges.computeIfAbsent(key) {
            Gauge
                .builder(LAG, lagSeconds) { it[key] ?: Double.NaN }
                .description("Age of the oldest event the consumer has not consumed, zero with nothing unconsumed")
                .baseUnit(SECONDS)
                .tags(CONSUMER, key.consumer, EVENT_TYPE, key.eventType)
                .strongReference(true)
                .register(registry)
        }
    }

    companion object {
        /**
         * The lag gauge, rendered by the Prometheus registry as `gst_platform_consumer_staleness_lag_seconds`. The
         * name is pending a ruling (see the module CHANGELOG, 3.2.0).
         */
        const val LAG = "gst.platform.consumer.staleness.lag"

        /** The threshold gauge, rendered as `gst_platform_consumer_staleness_threshold_seconds`. */
        const val THRESHOLD = "gst.platform.consumer.staleness.threshold"

        const val CONSUMER = "consumer"
        const val EVENT_TYPE = "event_type"
        private const val SECONDS = "seconds"
        private const val NANOS_PER_SECOND = 1e9

        private fun seconds(d: Duration): Double = d.toNanos() / NANOS_PER_SECOND

        private val log = LoggerFactory.getLogger(StalenessGauges::class.java)
    }
}
