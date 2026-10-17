package com.dynokore.platform.calendarandperiods

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ObligationClocksTest {
    private fun at(hhmm: String) = Instant.parse("2026-04-01T$hhmm:00Z")

    private fun schedule(hardStop: Instant) =
        escalationSchedule(at("10:00"), Duration.ofMinutes(30), Duration.ofHours(4), hardStop)

    @Test
    fun `hard stop before requested T2 pulls T2 forward and t1 is not before t2`() {
        val s = schedule(at("11:00"))
        assertEquals(at("10:30"), s.t1)
        assertEquals(at("14:00"), s.requestedT2)
        assertEquals(at("11:00"), s.t2)
        assertTrue(s.t2PulledForward)
        assertFalse(s.t1NotBeforeT2)
        assertFalse(s.hardStopPassed)
    }

    @Test
    fun `distant hard stop leaves T2 as requested`() {
        val s = schedule(at("20:00"))
        assertEquals(at("14:00"), s.t2)
        assertFalse(s.t2PulledForward)
        assertFalse(s.t1NotBeforeT2)
    }

    @Test
    fun `hard stop before T1 does not cap T1`() {
        val s = schedule(at("10:20"))
        assertEquals(at("10:30"), s.t1)
        assertEquals(at("10:20"), s.t2)
        assertTrue(s.t1NotBeforeT2)
    }

    @Test
    fun `hard stop at or before the send time is passed`() {
        val s = schedule(at("09:00"))
        assertTrue(s.hardStopPassed)
        assertEquals(at("09:00"), s.t2)
        assertTrue(schedule(at("10:00")).hardStopPassed)
        assertFalse(schedule(at("10:01")).hardStopPassed)
    }

    @Test
    fun `requested T2 equal to the hard stop is not pulled forward, and t1 equal to t2 is not before`() {
        assertFalse(schedule(at("14:00")).t2PulledForward)
        assertTrue(schedule(at("10:30")).t1NotBeforeT2)
    }

    @Test
    fun `escalation durations are validated`() {
        val h = at("20:00")
        val sent = at("10:00")
        assertThrows(
            IllegalArgumentException::class.java,
        ) { escalationSchedule(sent, Duration.ZERO, Duration.ofHours(1), h) }
        assertThrows(IllegalArgumentException::class.java) {
            escalationSchedule(sent, Duration.ofMinutes(-1), Duration.ofHours(1), h)
        }
        assertThrows(IllegalArgumentException::class.java) {
            escalationSchedule(sent, Duration.ofHours(1), Duration.ofHours(1), h)
        }
        assertThrows(IllegalArgumentException::class.java) {
            escalationSchedule(sent, Duration.ofHours(2), Duration.ofHours(1), h)
        }
    }

    @Test
    fun `hard stop is due minus margin and a negative margin is refused`() {
        val zone = ZoneId.of("Asia/Singapore")
        val due = ZonedDateTime.of(2026, 4, 30, 23, 59, 59, 0, zone)
        assertEquals(
            ZonedDateTime.of(2026, 4, 28, 23, 59, 59, 0, zone).toInstant(),
            hardStop(due, Duration.ofHours(48)),
        )
        assertEquals(due.toInstant(), hardStop(due, Duration.ZERO))
        assertThrows(IllegalArgumentException::class.java) { hardStop(due, Duration.ofSeconds(-1)) }
    }

    @Test
    fun `cap returns the earlier instant`() {
        assertEquals(at("10:00"), capAtHardStop(at("10:00"), at("11:00")))
        assertEquals(at("11:00"), capAtHardStop(at("12:00"), at("11:00")))
        assertEquals(at("11:00"), capAtHardStop(at("11:00"), at("11:00")))
    }

    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun `response deadline follows the pack default or a tighter tenant period`() {
        assertEquals(d("2026-03-31"), responseDeadline(d("2026-03-01"), 30, null))
        assertEquals(d("2026-01-31"), responseDeadline(d("2026-01-01"), 30, null))
        assertEquals(d("2026-03-15"), responseDeadline(d("2026-03-01"), 30, 14))
        assertEquals(d("2026-03-31"), responseDeadline(d("2026-03-01"), 30, 30))
    }

    @Test
    fun `response deadline refuses a longer tenant period and non-positive periods`() {
        val r = d("2026-03-01")
        assertThrows(IllegalArgumentException::class.java) { responseDeadline(r, 30, 31) }
        assertThrows(IllegalArgumentException::class.java) { responseDeadline(r, 30, 0) }
        assertThrows(IllegalArgumentException::class.java) { responseDeadline(r, 30, -1) }
        assertThrows(IllegalArgumentException::class.java) { responseDeadline(r, 0, null) }
        assertThrows(IllegalArgumentException::class.java) { responseDeadline(r, 0, 0) }
    }

    @Test
    fun `notice is due from deadline minus lead and stays due when overdue`() {
        val deadline = d("2026-03-31")
        assertFalse(noticeDue(deadline, d("2026-03-23"), 7))
        assertTrue(noticeDue(deadline, d("2026-03-24"), 7))
        assertTrue(noticeDue(deadline, d("2026-03-31"), 7))
        assertTrue(noticeDue(deadline, d("2026-04-01"), 7))
        assertTrue(noticeDue(deadline, deadline, 0))
        assertFalse(noticeDue(deadline, d("2026-03-30"), 0))
        assertThrows(IllegalArgumentException::class.java) { noticeDue(deadline, deadline, -1) }
    }
}
