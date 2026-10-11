package com.dynokore.platform.serviceruntime.jobs

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** The run window of a run-now is the schedule interval containing the request (INV-Q-3034). */
class CronTicksTest {
    private val hourly = CronTicks(JobSchedule("0 0 * * * *"))

    @Test
    fun `the tick is the latest firing at or before the instant`() {
        hourly.tickContaining(Instant.parse("2026-10-11T09:12:03Z")) shouldBe Instant.parse("2026-10-11T09:00:00Z")
        hourly.tickContaining(Instant.parse("2026-10-11T09:00:00Z")) shouldBe Instant.parse("2026-10-11T09:00:00Z")
        hourly.next(Instant.parse("2026-10-11T09:00:00Z")) shouldBe Instant.parse("2026-10-11T10:00:00Z")
    }

    @Test
    fun `a schedule in a zone, and a monthly schedule, give their own windows`() {
        val daily = CronTicks(JobSchedule("0 30 8 * * *", ZoneId.of("Asia/Singapore")))
        daily.tickContaining(Instant.parse("2026-10-11T00:00:00Z")) shouldBe
            Instant.parse("2026-10-11T00:30:00Z").minus(Duration.ofDays(1))
        val monthly = CronTicks(JobSchedule("0 0 0 1 * *"))
        monthly.tickContaining(Instant.parse("2026-10-11T00:00:00Z")) shouldBe Instant.parse("2026-10-01T00:00:00Z")
        monthly.longestInterval(Instant.parse("2026-01-01T00:00:00Z")) shouldBe Duration.ofDays(31)
    }

    @Test
    fun `a schedule that is not a six-field cron expression is refused`() {
        assertThrows<IllegalArgumentException> { CronTicks(JobSchedule("PT1H")) }
        assertThrows<IllegalArgumentException> { CronTicks(JobSchedule("0 * * * *")) }
    }
}
