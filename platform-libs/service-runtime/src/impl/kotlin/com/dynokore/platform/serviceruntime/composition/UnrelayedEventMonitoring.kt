package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.outboxandqueue.Retention
import com.dynokore.platform.outboxandqueue.UnrelayedEvent
import com.dynokore.platform.outboxandqueue.UnrelayedEventCheck
import com.dynokore.platform.outboxandqueue.UnrelayedEventSettings
import com.dynokore.platform.outboxandqueue.UnrelayedEventSink
import com.dynokore.platform.serviceruntime.pool.PoolLane
import com.dynokore.platform.serviceruntime.pool.PoolLanes
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.SmartLifecycle
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * `service-runtime.unrelayed-events.*` (CS-27), per installation profile: the check of outbox rows marked published
 * whose event never reached the event log (owner rulings INV-Q-4334 (B), INV-Q-4350 (A)).
 *
 * @property checkInterval how often the check runs; default 5 minutes.
 * @property margin how long after the event log's retention cut-off a row must have been published to be reported
 *   ([UnrelayedEventSettings.margin], coordinator ruling INV-Q-4403 (A)); default 1 hour. Validated at start-up against
 *   the installation's [Retention] (or, without one, the 7-day floor any retention keeps): not positive or not below
 * the
 *   shortest topic retention stops start-up.
 */
@ConfigurationProperties(prefix = UnrelayedEventProperties.PREFIX)
data class UnrelayedEventProperties(
    val checkInterval: Duration = DEFAULT_CHECK_INTERVAL,
    val margin: Duration = UnrelayedEventSettings.DEFAULT_MARGIN,
) {
    init {
        require(!checkInterval.isZero && !checkInterval.isNegative) { "$PREFIX.check-interval must be positive" }
    }

    /** The check's settings for [retention], or the 7-day floor without one; refuses an invalid [margin]. */
    fun settings(retention: Retention?): UnrelayedEventSettings =
        if (retention != null) {
            UnrelayedEventSettings.forRetention(retention, margin)
        } else {
            UnrelayedEventSettings(Retention.FLOOR, margin)
        }

    companion object {
        const val PREFIX = "service-runtime.unrelayed-events"
        val DEFAULT_CHECK_INTERVAL: Duration = Duration.ofMinutes(5)
    }
}

/**
 * The default [UnrelayedEventSink]: an ERROR log line naming the service, topic, event id, outbox seq and
 * `published_at`, once per row across instances and restarts (the check records each raised row durably). A service's
 * own sink replaces it.
 */
class LoggingUnrelayedEventSink : UnrelayedEventSink {
    override fun raise(finding: UnrelayedEvent) {
        log.error(
            "outbox row marked published but its event is not in the event log: service={} topic={} event_id={} " +
                "outbox_seq={} published_at={}",
            finding.service,
            finding.topic,
            finding.eventId,
            finding.outboxSeq,
            finding.publishedAt,
        )
    }

    private companion object {
        private val log = LoggerFactory.getLogger(LoggingUnrelayedEventSink::class.java)
    }
}

/**
 * Exports the open findings of the [UnrelayedEventCheck] as one gauge, [OPEN], labelled with the service only (CS-36),
 * as [com.dynokore.platform.serviceruntime.staleness.StalenessGauges] does for staleness (INV-REQ-1165). When a check
 * fails the value is withdrawn (NaN) until the next success, so a check that cannot run reads as absent, never as zero.
 */
class UnrelayedEventGauge(
    private val check: UnrelayedEventCheck,
    service: String,
    registry: MeterRegistry,
) {
    private val value = AtomicReference(Double.NaN)

    init {
        Gauge
            .builder(OPEN, value) { it.get() }
            .description("Outbox rows marked published whose event is not in the event log")
            .tags(SERVICE, service)
            .strongReference(true)
            .register(registry)
    }

    /** Runs the check and updates the gauge; returns the open count, or null when the check failed. */
    @Suppress("TooGenericExceptionCaught")
    fun refresh(): Int? =
        try {
            check
                .check()
                .open.size
                .also { value.set(it.toDouble()) }
        } catch (e: Exception) {
            log.error("unrelayed-event check failed, gauge withdrawn: {}", e.javaClass.simpleName)
            value.set(Double.NaN)
            null
        }

    companion object {
        /** Rendered by the Prometheus registry as `gst_platform_outbox_unrelayed_events_open`. */
        const val OPEN = "gst.platform.outbox.unrelayed.events.open"
        const val SERVICE = "service"
        private val log = LoggerFactory.getLogger(UnrelayedEventGauge::class.java)
    }
}

/** Runs [UnrelayedEventGauge.refresh] at start and then every [interval] in the background. */
class UnrelayedEventCheckSchedule(
    private val gauge: UnrelayedEventGauge,
    private val interval: Duration,
) : SmartLifecycle {
    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    override fun start() {
        if (scheduler != null) return
        scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name(NAME).factory()).also {
                it.scheduleWithFixedDelay({
                    PoolLanes.inLane(PoolLane.BACKGROUND) { gauge.refresh() }
                }, 0, interval.toMillis(), TimeUnit.MILLISECONDS)
            }
    }

    override fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    override fun isRunning(): Boolean = scheduler != null

    companion object {
        const val NAME = "unrelayed-event-check"
    }
}
