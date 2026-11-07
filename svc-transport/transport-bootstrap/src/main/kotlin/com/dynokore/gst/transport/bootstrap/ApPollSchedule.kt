package com.dynokore.gst.transport.bootstrap

import java.time.Duration

/**
 * The six-field cron expression of a poll interval (INV-Q-9684): the job runtime declares a schedule as cron, so an
 * interval must be a whole divisor of its unit: seconds (1..30 dividing 60), minutes (1..30 dividing 60) or hours
 * (1..12 dividing 24). Any other interval is refused at start-up rather than rounded.
 */
object ApPollSchedule {
    private const val MINUTE = 60L
    private const val HOUR = 3600L
    private const val DAY = 86400L

    fun cron(interval: Duration): String {
        val s = interval.seconds
        require(interval.nano == 0 && s > 0) { "transport.ap.poll-interval must be a whole number of seconds" }
        return when {
            s < MINUTE -> {
                "*/$s * * * * *".also { require(MINUTE % s == 0L) { divisor(interval) } }
            }

            s < HOUR -> {
                require(s % MINUTE == 0L && HOUR % s == 0L) { divisor(interval) }
                "0 */${s / MINUTE} * * * *"
            }

            else -> {
                require(s % HOUR == 0L && DAY % s == 0L) { divisor(interval) }
                "0 0 */${s / HOUR} * * *"
            }
        }
    }

    private fun divisor(interval: Duration) =
        "transport.ap.poll-interval $interval must divide a minute, an hour or a day into equal ticks"
}
