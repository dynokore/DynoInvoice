package com.dynokore.platform.serviceruntime.jobs

import org.springframework.scheduling.support.CronExpression
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

/**
 * The firings of a [JobSchedule]. [tickContaining] is the run window's tick: the latest firing at or
 * before an instant (INV-Q-3034, the schedule interval containing the request).
 */
class CronTicks(private val schedule: JobSchedule) {
    private val cron: CronExpression =
        try {
            CronExpression.parse(schedule.cron)
        } catch (invalid: IllegalArgumentException) {
            throw IllegalArgumentException("schedule is not a six-field cron expression", invalid)
        }

    init {
        requireNotNull(cron.next(ZonedDateTime.now(schedule.zone))) { "schedule never fires" }
    }

    fun next(after: Instant): Instant = checkNotNull(cron.next(after.atZone(schedule.zone))) { "schedule has no further firing" }.toInstant()

    fun tickContaining(at: Instant): Instant {
        var step = Duration.ofSeconds(1)
        while (step <= MAX_LOOKBACK) {
            val first = next(at.minus(step))
            if (!first.isAfter(at)) {
                var tick = first
                while (true) {
                    val following = next(tick)
                    if (following.isAfter(at)) return tick
                    tick = following
                }
            }
            step = step.multipliedBy(2)
        }
        throw IllegalStateException("schedule fired not within the lookback")
    }

    /** The longest gap between consecutive firings over the next [firings] firings after [from]. */
    fun longestInterval(
        from: Instant,
        firings: Int = 32,
    ): Duration {
        var tick = next(from)
        var longest = Duration.ZERO
        repeat(firings) {
            val following = next(tick)
            longest = maxOf(longest, Duration.between(tick, following))
            tick = following
        }
        return longest
    }

    private companion object {
        val MAX_LOOKBACK: Duration = Duration.ofDays(800)
    }
}
