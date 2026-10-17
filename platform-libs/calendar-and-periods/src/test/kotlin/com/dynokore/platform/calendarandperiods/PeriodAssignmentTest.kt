package com.dynokore.platform.calendarandperiods

import com.dynokore.platform.calendarandperiods.Fixtures.d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

class PeriodAssignmentTest {
    private val sg = ZoneId.of("Asia/Singapore")
    private val ny = ZoneId.of("America/New_York")
    private val utc = ZoneId.of("UTC")

    private fun window(
        start: String,
        end: String,
    ) = PeriodRecord(d(start), d(end), "F5", d(end), d(end), emptySet())

    private val march = window("2026-01-01", "2026-03-31")
    private val april = window("2026-04-01", "2026-06-30")
    private val quarters = PeriodSet.of(listOf(march, april))
    private val local = LocalDateTime.parse("2026-03-31T23:30")

    private fun explicit(zone: ZoneId) = TransactionTimestamp.Explicit(local.atZone(zone))

    private fun assign(
        ts: TransactionTimestamp,
        source: ZoneId = sg,
        set: PeriodSet = quarters,
    ) = assignPeriod(set, ts, sg, source)

    @Test
    fun `INV-AC-119 explicit timestamps are assigned by their boundary-zone date`() {
        val a = assign(explicit(sg)) as PeriodAssignment.Assigned
        assertEquals(march, a.period)
        assertEquals(d("2026-03-31"), a.boundaryDate)

        val b = assign(explicit(ny)) as PeriodAssignment.Assigned
        assertEquals(april, b.period)
        assertEquals(d("2026-04-01"), b.boundaryDate)

        val c =
            assign(
                TransactionTimestamp.Explicit(ZonedDateTime.of(2026, 3, 31, 16, 30, 0, 0, utc)),
            ) as PeriodAssignment.Assigned
        assertEquals(april, c.period)
    }

    @Test
    fun `INV-AC-199 a zone-less timestamp is read in the source zone and the assumption is traced`() {
        val zl = TransactionTimestamp.ZoneLess(local)
        val a = assign(zl, sg) as PeriodAssignment.Assigned
        assertEquals(march, a.period)
        assertTrue(a.trace.any { it == "assumed zone Asia/Singapore for a timestamp without a zone" })

        val b = assign(zl, ny) as PeriodAssignment.Assigned
        assertEquals(april, b.period)
        assertTrue(b.trace.any { it == "assumed zone America/New_York for a timestamp without a zone" })

        assertTrue(assign(explicit(sg)).trace.none { it.startsWith("assumed zone") })
    }

    @Test
    fun `INV-AC-118 the result does not depend on the JVM default zone or call order`() {
        val saved = TimeZone.getDefault()
        try {
            val first = assign(explicit(ny))
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            val second = assign(explicit(ny))
            val zl = assign(TransactionTimestamp.ZoneLess(local), ny)
            TimeZone.setDefault(TimeZone.getTimeZone("Etc/GMT+12"))
            assertEquals(first, second)
            assertEquals(zl, assign(TransactionTimestamp.ZoneLess(local), ny))
            assertEquals(first, assign(explicit(ny)))
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test
    fun `a date before the first window or in a gap gets NoPeriod naming the date`() {
        val before = assign(TransactionTimestamp.Explicit(ZonedDateTime.of(2025, 12, 31, 12, 0, 0, 0, sg)))
        assertEquals(PeriodAssignment.NoPeriod::class, before::class)
        assertEquals(d("2025-12-31"), before.boundaryDate)
        assertTrue(before.trace.any { "2025-12-31" in it && it.startsWith("no period") })

        val gapped = PeriodSet.of(listOf(march, window("2026-05-01", "2026-06-30")))
        val inGap = assign(TransactionTimestamp.Explicit(ZonedDateTime.of(2026, 4, 15, 12, 0, 0, 0, sg)), set = gapped)
        assertEquals(PeriodAssignment.NoPeriod(d("2026-04-15"), inGap.trace), inGap)

        assertTrue(assign(explicit(sg), set = PeriodSet.of(emptyList())) is PeriodAssignment.NoPeriod)
    }

    @Test
    fun `overlapping periods are refused and adjacent ones accepted`() {
        assertThrows(IllegalArgumentException::class.java) {
            PeriodSet.of(listOf(window("2026-01-01", "2026-03-31"), window("2026-03-31", "2026-06-30")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PeriodSet.of(listOf(window("2026-01-01", "2026-06-30"), window("2026-02-01", "2026-02-10")))
        }
        PeriodSet.of(listOf(window("2026-04-01", "2026-06-30"), window("2026-01-01", "2026-03-31")))
    }
}
