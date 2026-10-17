package com.dynokore.platform.integrationcanon

import com.dynokore.platform.integrationcanon.Fixtures.a
import com.dynokore.platform.integrationcanon.Fixtures.b
import com.dynokore.platform.integrationcanon.Fixtures.c
import com.dynokore.platform.integrationcanon.Fixtures.calendar
import com.dynokore.platform.integrationcanon.Fixtures.d
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SelectionTest {
    private fun sel(
        required: SpecVersion,
        date: java.time.LocalDate,
    ) = selectVersion(calendar, required, date)

    @Test
    fun `INV-AC-528 the oldest accepted version not older than the required one`() {
        assertEquals(VersionSelection.Selected(b), sel(a, d(2026, 3, 1)))
        assertEquals(VersionSelection.Selected(a), sel(a, d(2025, 7, 1)))
        assertEquals(VersionSelection.Selected(b), sel(b, d(2025, 7, 1)))
        assertEquals(VersionSelection.Selected(b), sel(b, d(2026, 7, 1)))
    }

    @Test
    fun `no accepted version names what was asked and the accepted list`() {
        assertEquals(VersionSelection.NoAcceptedVersion(c, d(2026, 3, 1), listOf(b)), sel(c, d(2026, 3, 1)))
        assertEquals(VersionSelection.NoAcceptedVersion(a, d(2023, 1, 1), emptyList()), sel(a, d(2023, 1, 1)))
    }

    @Test
    fun `INV-AC-1699 the calendar decides what is accepted on a date`() {
        assertEquals(listOf(a, b), calendar.acceptedOn(d(2025, 7, 1)))
        assertEquals(listOf(b), calendar.acceptedOn(d(2026, 1, 1)))
        assertEquals(listOf(b, c), calendar.acceptedOn(d(2026, 6, 1)))
        assertEquals(listOf(a, b), calendar.acceptedOn(d(2025, 12, 31)))
        assertEquals(listOf(b), calendar.acceptedOn(d(2026, 1, 1)))
        assertEquals(emptyList<SpecVersion>(), calendar.acceptedOn(d(2024, 1, 1)).filter { it != a })
    }

    @Test
    fun `accepted versions are sorted by release order, not entry order`() {
        val reversed = AcceptanceCalendar.of(Fixtures.order, calendar.entries.reversed())
        assertEquals(listOf(b, c), reversed.acceptedOn(d(2026, 6, 1)))
    }

    @Test
    fun `required version not in the order is refused`() {
        assertThrows(IllegalArgumentException::class.java) { sel(SpecVersion("spec-z"), d(2026, 3, 1)) }
    }

    private class Recording(
        private val unrepresentable: Map<SpecVersion, String>,
    ) : Representability {
        val calls = mutableListOf<SpecVersion>()

        override fun check(version: SpecVersion): RepresentabilityAnswer {
            calls += version
            return unrepresentable[version]?.let { RepresentabilityAnswer.Unrepresentable(it) }
                ?: RepresentabilityAnswer.Representable
        }
    }

    @Test
    fun `INV-AC-1587 and INV-AC-625 an unrepresentable selected version blocks and no other is tried`() {
        val rec = Recording(mapOf(b to "code X removed"))
        val r = selectRepresentable(calendar, a, d(2026, 3, 1), rec)
        assertEquals(RepresentableSelection.Blocked(b, "code X removed"), r)
        assertEquals(listOf(b), rec.calls)
    }

    @Test
    fun `a representable selected version is returned`() {
        val rec = Recording(emptyMap())
        assertEquals(RepresentableSelection.Selected(b), selectRepresentable(calendar, a, d(2026, 3, 1), rec))
        assertEquals(listOf(b), rec.calls)
    }

    @Test
    fun `no accepted version is passed through and the check is not called`() {
        val rec = Recording(emptyMap())
        assertEquals(
            RepresentableSelection.NoAcceptedVersion(c, d(2026, 3, 1), listOf(b)),
            selectRepresentable(calendar, c, d(2026, 3, 1), rec),
        )
        assertEquals(emptyList<SpecVersion>(), rec.calls)
    }
}
