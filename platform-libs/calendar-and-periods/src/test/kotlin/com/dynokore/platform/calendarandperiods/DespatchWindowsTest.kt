package com.dynokore.platform.calendarandperiods

import com.dynokore.platform.calendarandperiods.Fixtures.d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class DespatchWindowsTest {
    private fun i(text: String) = Instant.parse(text)

    private val stop = i("2026-04-20T00:00:00Z")
    private val ready = i("2026-04-18T09:00:00Z")
    private val plus8 = ZoneOffset.ofHours(8)

    @Test
    fun `INV-AC-179 continuous despatch goes at once and reports a late one`() {
        assertEquals(Despatch(ready, DespatchReason.IMMEDIATE_CONTINUOUS, false), despatchAt(ready, null, stop))
        val late = i("2026-04-21T00:00:00Z")
        assertEquals(Despatch(late, DespatchReason.IMMEDIATE_CONTINUOUS, true), despatchAt(late, null, stop))
    }

    @Test
    fun `INV-AC-190 a hard stop before the next opening despatches at once`() {
        val due = ZonedDateTime.of(2026, 4, 30, 0, 0, 0, 0, ZoneOffset.UTC)
        assertEquals(stop, hardStop(due, Duration.ofDays(10)))
        assertEquals(
            Despatch(ready, DespatchReason.IMMEDIATE_MARGIN, false),
            despatchAt(ready, i("2026-04-28T00:00:00Z"), stop),
        )
    }

    @Test
    fun `INV-AC-190 look-alikes - opening equal to the hard stop is a window, after it is immediate`() {
        assertEquals(
            Despatch(stop, DespatchReason.WINDOW, false),
            despatchAt(ready, i("2026-04-20T00:00:00Z"), stop),
        )
        assertEquals(
            Despatch(ready, DespatchReason.IMMEDIATE_MARGIN, false),
            despatchAt(ready, i("2026-04-21T00:00:00Z"), stop),
        )
    }

    @Test
    fun `INV-AC-190 ready after the hard stop is past the hard stop whatever the opening`() {
        val late = i("2026-04-21T00:00:00Z")
        assertEquals(
            Despatch(late, DespatchReason.IMMEDIATE_PAST_HARD_STOP, true),
            despatchAt(late, i("2026-04-28T00:00:00Z"), stop),
        )
    }

    @Test
    fun `INV-AC-190 an opening before readyAt is refused`() {
        assertThrows(IllegalArgumentException::class.java) { despatchAt(ready, i("2026-04-17T00:00:00Z"), stop) }
    }

    @Test
    fun `INV-AC-204 the two intervals add against the gap minus the margin`() {
        val gap = Duration.ofDays(17)
        val margin = Duration.ofDays(7)
        val week = BatchWindow.WEEKLY.interval
        assertFalse(weeklyWindowFits(week, week, gap, margin))
        assertTrue(weeklyWindowFits(week, BatchWindow.CONTINUOUS.interval, gap, margin))
        assertTrue(weeklyWindowFits(BatchWindow.DAILY.interval, week, gap, margin))
        assertTrue(weeklyWindowFits(week, week, Duration.ofDays(21), margin))
        assertFalse(weeklyWindowFits(week, week, Duration.ofDays(20), margin))
    }

    @Test
    fun `INV-AC-204 a negative argument is refused`() {
        val z = Duration.ZERO
        val n = Duration.ofDays(-1)
        assertThrows(IllegalArgumentException::class.java) { weeklyWindowFits(n, z, z, z) }
        assertThrows(IllegalArgumentException::class.java) { weeklyWindowFits(z, n, z, z) }
        assertThrows(IllegalArgumentException::class.java) { weeklyWindowFits(z, z, n, z) }
        assertThrows(IllegalArgumentException::class.java) { weeklyWindowFits(z, z, z, n) }
    }

    @Test
    fun `INV-AC-2267 daily extraction and weekly despatch exceed 6 days left`() {
        assertFalse(
            weeklyWindowFits(
                BatchWindow.DAILY.interval,
                BatchWindow.WEEKLY.interval,
                Duration.ofDays(16),
                Duration.ofDays(10),
            ),
        )
    }

    @Test
    fun `INV-AC-198 weekly extraction with continuous despatch needs 7 days left`() {
        val week = BatchWindow.WEEKLY.interval
        val none = BatchWindow.CONTINUOUS.interval
        assertFalse(weeklyWindowFits(week, none, Duration.ofDays(16), Duration.ofDays(10)))
        assertTrue(weeklyWindowFits(week, none, Duration.ofDays(17), Duration.ofDays(10)))
    }

    @Test
    fun `INV-AC-204 several-a-day intervals`() {
        assertEquals(Duration.ofHours(12), severalDailyInterval(2))
        assertEquals(Duration.ofHours(1), severalDailyInterval(24))
        assertThrows(IllegalArgumentException::class.java) { severalDailyInterval(1) }
        assertThrows(IllegalArgumentException::class.java) { severalDailyInterval(25) }
    }

    @Test
    fun `INV-AC-190 daily and weekly openings in the zone of after`() {
        val z = ZoneId.ofOffset("UTC", plus8)
        val midnight = ZonedDateTime.of(2026, 4, 18, 0, 0, 0, 0, z)
        assertEquals(midnight.toInstant(), nextWindowOpening(midnight, BatchWindow.DAILY))
        assertEquals(
            ZonedDateTime.of(2026, 4, 19, 0, 0, 0, 0, z).toInstant(),
            nextWindowOpening(midnight.plusSeconds(1), BatchWindow.DAILY),
        )
        val monday = ZonedDateTime.of(2026, 4, 20, 0, 0, 0, 0, z)
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        assertEquals(monday.toInstant(), nextWindowOpening(monday, BatchWindow.WEEKLY))
        assertEquals(
            ZonedDateTime.of(2026, 4, 27, 0, 0, 0, 0, z).toInstant(),
            nextWindowOpening(ZonedDateTime.of(2026, 4, 21, 10, 0, 0, 0, z), BatchWindow.WEEKLY),
        )
        assertEquals(
            ZonedDateTime.of(2026, 4, 27, 0, 0, 0, 0, z).toInstant(),
            nextWindowOpening(monday.plusSeconds(1), BatchWindow.WEEKLY),
        )
        assertEquals(
            ZonedDateTime.of(2026, 4, 20, 0, 0, 0, 0, z).toInstant(),
            nextWindowOpening(ZonedDateTime.of(2026, 4, 19, 23, 0, 0, 0, z), BatchWindow.WEEKLY),
        )
        assertNull(nextWindowOpening(midnight, BatchWindow.CONTINUOUS))
    }

    @Test
    fun `INV-AC-2267 shortestGap is the least unmoved gap`() {
        val f = FilingFrequency("M")
        val pack =
            CalendarPack(
                "t",
                mapOf(
                    f to
                        listOf(
                            StandardWindow(d("2026-01-01"), d("2026-01-31"), "R"),
                            StandardWindow(d("2026-02-01"), d("2026-02-28"), "R"),
                            StandardWindow(d("2026-03-01"), d("2026-03-31"), "R"),
                        ),
                ),
                "R",
                mapOf("R" to DueRule(1, true, 0)),
                BusinessCalendar(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), emptySet()),
                NonBusinessDayRule.NEXT_WORKING_DAY,
            )
        assertEquals(Duration.ofDays(28), shortestGap(pack, f))
        assertThrows(CalendarPackCoverageException::class.java) { shortestGap(pack, FilingFrequency("Q")) }
    }

    @Test
    fun `INV-AC-2267 shortestGap ignores the weekend move`() {
        val pack = Fixtures.pack(pinnedRule = NonBusinessDayRule.NEXT_WORKING_DAY)
        // 2026-05-31 is a Sunday: the adjusted due date is later, the gap is not.
        assertEquals(Duration.ofDays(28), shortestGap(pack, Fixtures.monthly))
        assertEquals(Duration.ofDays(30), shortestGap(Fixtures.pack(), Fixtures.quarterly))
    }

    @Test
    fun `INV-AC-2267 windowsOf is an immutable copy`() {
        val pack = Fixtures.pack()
        val list = pack.windowsOf(Fixtures.monthly)
        assertTrue(list.isNotEmpty())
        @Suppress("UNCHECKED_CAST")
        assertThrows(UnsupportedOperationException::class.java) { (list as MutableList<StandardWindow>).clear() }
        assertEquals(list, pack.windowsOf(Fixtures.monthly))
        assertTrue(pack.windowsOf(FilingFrequency("NONE")).isEmpty())
    }
}
