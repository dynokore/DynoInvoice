package com.dynokore.gst.reconciliation.bootstrap

import com.dynokore.platform.outboxandqueue.LaneLimits
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The partition-ordered queue (`reconciliation.work_queue`). service-runtime creates `WorkQueue` and
 * `LaneRateLimiter` when the service declares its lane limits. The skeleton sets none: a lane without an entry is
 * unlimited, and the task that owns a lane's rate adds its entry here.
 */
@Configuration(proxyBeanMethods = false)
class QueueConfiguration {
    @Bean
    fun laneLimits(): LaneLimits = LaneLimits()
}
