package com.dynokore.platform.serviceruntime.staleness

import com.dynokore.platform.serviceruntime.pool.PoolLane
import com.dynokore.platform.serviceruntime.pool.PoolLanes
import org.springframework.context.SmartLifecycle
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Runs [StalenessGauges.refresh] at start and then every [interval] ([StalenessProperties.checkInterval]), on every
 * instance: the measurement only reads the service's own consumer offsets and the event log.
 */
class StalenessCheckSchedule(
    private val gauges: StalenessGauges,
    private val interval: Duration,
) : SmartLifecycle {
    @Volatile
    private var scheduler: ScheduledExecutorService? = null

    override fun start() {
        if (scheduler != null) return
        scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name(NAME).factory()).also {
                it.scheduleWithFixedDelay({
                    PoolLanes.inLane(PoolLane.BACKGROUND) { gauges.refresh() }
                }, 0, interval.toMillis(), TimeUnit.MILLISECONDS)
            }
    }

    override fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
    }

    override fun isRunning(): Boolean = scheduler != null

    companion object {
        const val NAME = "consumer-staleness-check"
    }
}
