package com.dynokore.gst.transport.bootstrap

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration

/** The cron form of `transport.ap.poll-interval` (INV-Q-9684): equal ticks only, never a rounded interval. */
class ApPollScheduleTest {
    @Test
    fun `an interval of whole seconds, minutes or hours that divides its unit maps to its cron`() {
        ApPollSchedule.cron(Duration.ofSeconds(15)) shouldBe "*/15 * * * * *"
        ApPollSchedule.cron(Duration.ofMinutes(5)) shouldBe "0 */5 * * * *"
        ApPollSchedule.cron(Duration.ofHours(1)) shouldBe "0 0 */1 * * *"
        ApPollSchedule.cron(Duration.ofHours(6)) shouldBe "0 0 */6 * * *"
    }

    @Test
    fun `probe - zero, negative, fractional and non-dividing intervals are refused`() {
        listOf(
            Duration.ZERO,
            Duration.ofSeconds(-5),
            Duration.ofMillis(1500),
            Duration.ofSeconds(7),
            Duration.ofSeconds(90),
            Duration.ofMinutes(7),
            Duration.ofHours(5),
            Duration.ofHours(48),
        ).forEach { shouldThrow<IllegalArgumentException> { ApPollSchedule.cron(it) } }
    }
}
