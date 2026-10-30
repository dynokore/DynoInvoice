package com.dynokore.gst.access.domain.grant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class GrantExpiryNoticesTest {
    private val from = Instant.parse("2026-01-01T00:00:00Z")
    private val utc = ZoneId.of("UTC")

    private fun window(until: String?) = ValidityWindow(from, until?.let { Instant.parse(it) })

    private fun notices(vararg pairs: Pair<Int, String>) =
        pairs.map { ExpiryNotice(it.first, LocalDate.parse(it.second)) }

    @Test
    fun `INV-AC-13281 a grant ending on the 20th has notices on the 6th and the 17th`() {
        val schedule = GrantExpiryNotices.schedule(window("2026-11-20T10:00:00Z"), utc, listOf(14, 3), 3)
        assertEquals(LocalDate.parse("2026-11-20"), schedule?.expiryDate)
        assertEquals(notices(14 to "2026-11-06", 3 to "2026-11-17"), schedule?.notices)
    }

    @Test
    fun `INV-AC-13281 the expiry date is read in the tenant zone`() {
        val kiritimati = ZoneId.of("Pacific/Kiritimati")
        val schedule = GrantExpiryNotices.schedule(window("2026-11-20T10:00:00Z"), kiritimati, listOf(14, 3), 3)
        assertEquals(LocalDate.parse("2026-11-21"), schedule?.expiryDate)
        assertEquals(notices(14 to "2026-11-07", 3 to "2026-11-18"), schedule?.notices)
    }

    @Test
    fun `INV-AC-13281 an open-ended grant has no schedule`() {
        assertNull(GrantExpiryNotices.schedule(window(null), utc, listOf(14, 3), 3))
    }

    @Test
    fun `INV-AC-13282 days 1 and 2 with lead 3 are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            GrantExpiryNotices.schedule(window("2026-11-20T10:00:00Z"), utc, listOf(1, 2), 3)
        }
    }

    @Test
    fun `INV-AC-13282 a lead below 1 is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            GrantExpiryNotices.schedule(window("2026-11-20T10:00:00Z"), utc, listOf(1, 2), 0)
        }
    }

    @Test
    fun `INV-AC-13282 days 14,3 and 1,3 with lead 3 are accepted`() {
        val until = window("2026-11-20T10:00:00Z")
        assertEquals(2, GrantExpiryNotices.schedule(until, utc, listOf(14, 3), 3)?.notices?.size)
        assertEquals(
            notices(3 to "2026-11-17", 1 to "2026-11-19"),
            GrantExpiryNotices.schedule(until, utc, listOf(1, 3), 3)?.notices,
        )
    }

    @Test
    fun `INV-AC-13283 days 21,7,3 give three notices in date order`() {
        val schedule = GrantExpiryNotices.schedule(window("2026-11-28T10:00:00Z"), utc, listOf(21, 7, 3), 3)
        assertEquals(notices(21 to "2026-11-07", 7 to "2026-11-21", 3 to "2026-11-25"), schedule?.notices)
    }

    @Test
    fun `INV-AC-13283 unordered days are returned in date order`() {
        val schedule = GrantExpiryNotices.schedule(window("2026-11-28T10:00:00Z"), utc, listOf(3, 21, 7), 3)
        assertEquals(notices(21 to "2026-11-07", 7 to "2026-11-21", 3 to "2026-11-25"), schedule?.notices)
    }

    @Test
    fun `INV-AC-13283 empty, repeated and zero days are refused`() {
        val until = window("2026-11-28T10:00:00Z")
        listOf(emptyList(), listOf(14, 14), listOf(14, 0)).forEach { days ->
            assertThrows(IllegalArgumentException::class.java) { GrantExpiryNotices.schedule(until, utc, days, 3) }
        }
    }
}
