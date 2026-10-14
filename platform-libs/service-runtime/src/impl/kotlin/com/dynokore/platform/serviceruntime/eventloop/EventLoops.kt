package com.dynokore.platform.serviceruntime.eventloop

import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.OutboxRelay
import com.dynokore.platform.outboxandqueue.ReplayDelivery
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ListableBeanFactory
import org.springframework.boot.availability.AvailabilityChangeEvent
import org.springframework.boot.availability.ReadinessState
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ApplicationListener
import org.springframework.context.SmartLifecycle
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import java.util.function.DoubleSupplier

/**
 * Drives every [OutboxRelay], [InboxConsumer] and [ReplayDelivery] bean of the application context, those
 * OutboxAndQueueWiring creates and those a service declares itself, one [EventLoop] per bean (INV-T-3938, coordinator
 * ruling INV-Q-3935; INV-REQ-1212 'a relay process per service', INV-REQ-1222). The loops start when the application
 * is ready ([ApplicationReadyEvent]) and stop on shutdown, as a lifecycle bean, before the DataSources are closed: each
 * finishes the call in hand.
 *
 * Several instances of one service need no lock here: the relay runs one at a time (a transaction-scoped advisory
 * lock) and a consumer locks its offset row, both in outbox-and-queue.
 */
class EventLoops(
    private val beans: ListableBeanFactory,
    private val properties: EventLoopProperties,
    private val registry: MeterRegistry?,
    private val events: ApplicationEventPublisher,
    private val clock: Clock = Clock.systemUTC(),
    private val jitter: DoubleSupplier = DoubleSupplier { ThreadLocalRandom.current().nextDouble() },
    private val stopTimeout: Duration = STOP_TIMEOUT,
) : SmartLifecycle,
    ApplicationListener<ApplicationReadyEvent> {
    @Volatile
    private var loops: List<EventLoop> = emptyList()

    @Volatile
    private var running = false

    private val meters = mutableListOf<io.micrometer.core.instrument.Meter.Id>()

    override fun onApplicationEvent(event: ApplicationReadyEvent) = start()

    @Synchronized
    override fun start() {
        if (running) return
        loops =
            loopsFor(OutboxRelay::class.java, EventLoopKind.RELAY, properties.relayInterval) {
                it.runOnce().published > 0
            } +
            loopsFor(InboxConsumer::class.java, EventLoopKind.CONSUMER, properties.consumerInterval) {
                it.pollOnce().total > 0
            } +
            loopsFor(
                ReplayDelivery::class.java,
                EventLoopKind.REPLAY,
                properties.replayInterval,
            ) { it.runOnce() != null }
        loops.forEach {
            register(it)
            it.start()
        }
        running = true
        log.info("event loops started: relay/consumer/replay loops={}", loops.size)
    }

    /** Stops every loop after its call in hand, before the context closes the DataSources. */
    @Synchronized
    override fun stop() {
        if (!running) return
        running = false
        loops.forEach { it.requestStop() }
        loops.forEach { it.awaitStop(stopTimeout) }
        registry?.let { r -> meters.forEach { r.remove(it) } }
        meters.clear()
        log.info("event loops stopped: loops={}", loops.size)
    }

    override fun isRunning(): Boolean = running

    /** Not started with the context: the loops start on [ApplicationReadyEvent]; the lifecycle only stops them. */
    override fun isAutoStartup(): Boolean = false

    /** The loops, for the readiness indicator and tests. */
    fun loops(): List<EventLoop> = loops

    private fun <T : Any> loopsFor(
        type: Class<T>,
        kind: EventLoopKind,
        interval: Duration,
        call: (T) -> Boolean,
    ): List<EventLoop> =
        beans
            .getBeansOfType(type)
            .toSortedMap()
            .map { (name, bean) -> EventLoop(name, kind, interval, clock, jitter, ::refuseTraffic) { call(bean) } }

    /** A loop that ended makes readiness DOWN whatever the service's health group lists (INV-T-3938). */
    private fun refuseTraffic(ended: EventLoop) {
        AvailabilityChangeEvent.publish(events, ended, ReadinessState.REFUSING_TRAFFIC)
    }

    private fun register(loop: EventLoop) {
        registry ?: return
        meters +=
            Gauge
                .builder(LAST_SUCCESS, loop) { it.lastSuccess()?.epochSecond?.toDouble() ?: Double.NaN }
                .description("Epoch seconds of the last call of an event loop that returned normally")
                .tags("loop", loop.name, "kind", loop.kind.label)
                .strongReference(true)
                .register(registry)
                .id
    }

    companion object {
        /** Rendered by the Prometheus registry as `gst_platform_event_loop_last_success_timestamp_seconds`. */
        const val LAST_SUCCESS = "gst.platform.event.loop.last.success.timestamp.seconds"
        val STOP_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val log = LoggerFactory.getLogger(EventLoops::class.java)
    }
}
