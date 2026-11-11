package com.dynokore.gst.consoleBff.adapters.ratelimit

import com.dynokore.gst.consoleBff.domain.ratelimit.ConsoleRateLimiter
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.InitializingBean
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Drops idle protective-limit counters once a window, so memory stays bounded by the traffic of the last window. */
class RateLimitEviction(
    private val limiter: ConsoleRateLimiter,
) : InitializingBean,
    DisposableBean {
    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "console-rate-limit-eviction").apply { isDaemon = true }
        }

    override fun afterPropertiesSet() {
        val period = ConsoleRateLimiter.WINDOW.toMillis()
        executor.scheduleWithFixedDelay(limiter::evictIdle, period, period, TimeUnit.MILLISECONDS)
    }

    override fun destroy() {
        executor.shutdownNow()
    }
}
