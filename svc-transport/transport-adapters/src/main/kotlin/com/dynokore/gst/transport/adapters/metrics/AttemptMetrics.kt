package com.dynokore.gst.transport.adapters.metrics

import com.dynokore.gst.transport.application.AccessPointAdapters
import com.dynokore.gst.transport.application.AttemptLog
import com.dynokore.gst.transport.domain.AdapterId
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import java.time.Clock
import java.time.Duration

/**
 * The failure metrics of each adapter, computed from the attempt log at every scrape (INV-REQ-565 behaviour 3):
 * `transport_ap_failure_rate` is failed attempts divided by attempts in the window, and the two counts it is made
 * of. Queue depth and oldest-unsent age belong to submission-svc, which owns the queue, and are not exposed here.
 */
class AttemptMetrics(
    private val adapters: AccessPointAdapters,
    private val attempts: AttemptLog,
    private val window: Duration,
    private val clock: Clock,
) : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        adapters.ids().forEach { adapter ->
            gauge(registry, "transport.ap.failure.rate", adapter) { attempts.counts(adapter, since()).failureRate }
            gauge(
                registry,
                "transport.ap.attempts.window",
                adapter,
            ) { attempts.counts(adapter, since()).total.toDouble() }
            gauge(registry, "transport.ap.failed.attempts.window", adapter) {
                attempts.counts(adapter, since()).failed.toDouble()
            }
        }
    }

    private fun since() = clock.instant().minus(window)

    private fun gauge(
        registry: MeterRegistry,
        name: String,
        adapter: AdapterId,
        value: () -> Double,
    ) {
        Gauge.builder(name) { value() }.tag("adapter", adapter.value).register(registry)
    }
}
