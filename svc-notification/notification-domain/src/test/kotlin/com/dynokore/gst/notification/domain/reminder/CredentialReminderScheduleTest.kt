package com.dynokore.gst.notification.domain.reminder

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class CredentialReminderScheduleTest {
    private val policy = ReminderPolicy(1, 90, 5, 3)
    private val current = ReminderSchedule(listOf(14, 3))

    private fun set(vararg days: Int) = CredentialReminderSchedule.set(days.toList(), current, policy)

    private fun refused() = ScheduleDecision.Refused("POL-013", current)

    @Test
    fun `INV-AC-2492 expiry on the 20th gives reminders on the 6th and the 17th`() {
        CredentialReminderSchedule.dates(LocalDate.of(2026, 10, 20), current) shouldBe
            listOf(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 17))
    }

    @Test
    fun `INV-AC-2493 ten and three days is accepted`() {
        set(3, 10) shouldBe ScheduleDecision.Accepted(ReminderSchedule(listOf(10, 3)))
    }

    @Test
    fun `INV-AC-2493 two and one days is refused and the current schedule stays`() {
        set(2, 1) shouldBe refused()
    }

    @Test
    fun `empty request is refused`() {
        set() shouldBe refused()
    }

    @Test
    fun `entry below the minimum is refused`() {
        set(0) shouldBe refused()
    }

    @Test
    fun `entry above the maximum is refused`() {
        set(91) shouldBe refused()
    }

    @Test
    fun `duplicate entry is refused`() {
        set(5, 5) shouldBe refused()
    }

    @Test
    fun `more entries than the maximum is refused`() {
        set(10, 9, 8, 7, 6, 5) shouldBe refused()
    }

    @Test
    fun `lead equal to the minimum is accepted`() {
        set(3) shouldBe ScheduleDecision.Accepted(ReminderSchedule(listOf(3)))
    }

    @Test
    fun `lead below the minimum is refused`() {
        set(2) shouldBe refused()
    }

    @Test
    fun `dates cross a month boundary`() {
        CredentialReminderSchedule.dates(LocalDate.of(2026, 3, 2), ReminderSchedule(listOf(5))) shouldBe
            listOf(LocalDate.of(2026, 2, 25))
    }

    @Test
    fun `invalid policies and empty schedule are rejected`() {
        shouldThrow<IllegalArgumentException> { ReminderPolicy(0, 90, 5, 3) }
        shouldThrow<IllegalArgumentException> { ReminderPolicy(1, 90, 5, 91) }
        shouldThrow<IllegalArgumentException> { ReminderSchedule(emptyList()) }
    }
}
