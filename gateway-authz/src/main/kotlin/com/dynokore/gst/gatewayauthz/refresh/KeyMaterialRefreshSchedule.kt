package com.dynokore.gst.gatewayauthz.refresh

import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Runs [refresher] on one thread with a fixed delay of [interval] between the end of one refresh and the start of the
 * next, so exactly one request is in flight (INV-API-4001) and, with a two-second x-timeout per read, the list is
 * re-read at least once a second whenever access-svc answers in time (coordinator ruling INV-Q-3973 A).
 */
class KeyMaterialRefreshSchedule(
    private val refresher: KeyMaterialRefresher,
    private val interval: Duration,
) : InitializingBean,
    DisposableBean {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "key-material-refresh").apply { isDaemon = true }
        }

    init {
        require(!interval.isNegative && !interval.isZero && interval <= MAX_INTERVAL) {
            "gateway-authz.refresh.interval must be above zero and at most $MAX_INTERVAL (INV-Q-3973 A)"
        }
    }

    override fun afterPropertiesSet() {
        executor.scheduleWithFixedDelay({ refresher.refreshOnce() }, 0, interval.toMillis(), TimeUnit.MILLISECONDS)
    }

    override fun destroy() {
        executor.shutdownNow()
    }

    companion object {
        /** At least once a second (INV-Q-3973 A). */
        val MAX_INTERVAL: Duration = Duration.ofSeconds(1)
    }
}
