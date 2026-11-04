package com.dynokore.platform.calendarandperiods

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

class DeadlineModeTest {
    private val due = LocalDate.of(2026, 11, 30)
    private val gate = hardStop(ZonedDateTime.of(2026, 11, 30, 16, 0, 0, 0, ZoneOffset.UTC), Duration.ofHours(6))

    @Test
    fun `INV-AC-15749 window 3 starts on 2026-11-27 and stays on after the due date`() {
        assertFalse(inDeadlineMode(due, LocalDate.of(2026, 11, 26), 3))
        assertTrue(inDeadlineMode(due, LocalDate.of(2026, 11, 27), 3))
        assertTrue(inDeadlineMode(due, LocalDate.of(2026, 11, 30), 3))
        assertTrue(inDeadlineMode(due, LocalDate.of(2026, 12, 2), 3))
    }

    @Test
    fun `INV-AC-15749 window 10 is in mode on 2026-11-20`() {
        assertTrue(inDeadlineMode(due, LocalDate.of(2026, 11, 20), 10))
    }

    @Test
    fun `INV-AC-15749 window below 1 is refused`() {
        assertThrows<IllegalArgumentException> { inDeadlineMode(due, due, 0) }
        assertThrows<IllegalArgumentException> { inDeadlineMode(due, due, -1) }
        assertThrows<IllegalArgumentException> { deadlineOrder(emptyList(), due, 0) }
    }

    @Test
    fun `INV-AC-15749 deadlineOrder puts periods in mode first by due date`() {
        val periods =
            listOf(
                PeriodDue("2026-Q2", LocalDate.of(2027, 2, 28)),
                PeriodDue("2026-Q1", due),
                PeriodDue("2025-Q4", LocalDate.of(2026, 8, 31)),
            )
        assertEquals(
            listOf("2025-Q4", "2026-Q1", "2026-Q2"),
            deadlineOrder(periods, LocalDate.of(2026, 11, 27), 3),
        )
    }

    @Test
    fun `INV-AC-15749 deadlineOrder puts a period out of mode after one in mode`() {
        val periods = listOf(PeriodDue("A", LocalDate.of(2026, 12, 31)), PeriodDue("B", due))
        assertEquals(listOf("B", "A"), deadlineOrder(periods, LocalDate.of(2026, 11, 27), 3))
        assertEquals(listOf("B", "A"), deadlineOrder(periods.reversed(), LocalDate.of(2026, 11, 20), 3))
    }

    @Test
    fun `INV-AC-15749 same due date is ordered by periodRef text`() {
        val periods = listOf(PeriodDue("b", due), PeriodDue("a", due), PeriodDue("B", due))
        assertEquals(listOf("B", "a", "b"), deadlineOrder(periods, LocalDate.of(2026, 11, 27), 3))
    }

    @Test
    fun `INV-AC-15749 duplicate periodRef is refused`() {
        assertThrows<IllegalArgumentException> {
            deadlineOrder(listOf(PeriodDue("x", due), PeriodDue("x", due.plusDays(1))), due, 3)
        }
    }

    @Test
    fun `INV-AC-15749 hoursToGate is 70 hours and not passed`() {
        assertEquals(
            HoursToGate(70, false),
            hoursToGate(gate, Instant.parse("2026-11-27T12:00:00Z")),
        )
    }

    @Test
    fun `INV-AC-15749 hoursToGate after the gate is zero and passed`() {
        assertEquals(
            HoursToGate(0, true),
            hoursToGate(gate, Instant.parse("2026-11-30T10:00:00.500Z")),
        )
        assertEquals(HoursToGate(0, true), hoursToGate(gate, gate))
    }

    @Test
    fun `INV-AC-15749 hoursToGate rounds down 59 minutes to zero not passed`() {
        assertEquals(
            HoursToGate(0, false),
            hoursToGate(gate, Instant.parse("2026-11-30T09:01:00Z")),
        )
    }
}
