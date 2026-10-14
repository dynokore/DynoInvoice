package com.dynokore.platform.serviceruntime.eventloop

import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator

/**
 * The `eventLoops` readiness contributor (INV-T-3938): DOWN when a loop has ended on an Error it could not survive,
 * naming the loops. A loop that is retrying a failing call is still running, and the service stays ready (the failure
 * is logged and counted; the staleness gauges show the lag).
 */
class EventLoopHealthIndicator(
    private val loops: () -> EventLoops?,
) : HealthIndicator {
    override fun health(): Health {
        val all = loops()?.loops().orEmpty()
        val ended = all.filter { it.ended() != null }
        return if (ended.isEmpty()) {
            Health.up().withDetail("loops", all.size).build()
        } else {
            Health.down().withDetail("ended", ended.map { "${it.kind.label}:${it.name}" }).build()
        }
    }
}
