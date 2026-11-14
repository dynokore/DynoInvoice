package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.application.PackCurrencyMonitor
import com.dynokore.gst.rules.application.PackFeedPoller
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled

/**
 * The short tick that drives the feed poll and the daily DEP-001 evaluation (INV-REQ-1075): both decide on the
 * injected clock, so a tick that finds nothing due does nothing. A failure of either is logged and never escapes: the
 * data plane keeps serving on the active pack.
 */
class PackCurrencyScheduling(
    private val poller: PackFeedPoller?,
    private val monitor: PackCurrencyMonitor,
) {
    @Scheduled(fixedDelay = TICK_MILLIS, initialDelay = TICK_MILLIS)
    fun tick() {
        runCatching { poller?.tick() }.onFailure { log.warn("pack feed poll failed: {}", it.javaClass.simpleName) }
        runCatching { monitor.tick() }.onFailure { log.warn("pack currency check failed: {}", it.javaClass.simpleName) }
    }

    private companion object {
        const val TICK_MILLIS = 30_000L
        val log = LoggerFactory.getLogger(PackCurrencyScheduling::class.java)
    }
}
