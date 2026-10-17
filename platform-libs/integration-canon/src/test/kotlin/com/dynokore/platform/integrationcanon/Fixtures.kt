package com.dynokore.platform.integrationcanon

import java.time.LocalDate

object Fixtures {
    val a = SpecVersion("spec-a")
    val b = SpecVersion("spec-b")
    val c = SpecVersion("spec-c")
    val order = ReleaseOrder.of(listOf(a, b, c))
    val calendar =
        AcceptanceCalendar.of(
            order,
            listOf(
                AcceptanceEntry(a, LocalDate.of(2024, 1, 1), LocalDate.of(2025, 12, 31)),
                AcceptanceEntry(b, LocalDate.of(2025, 6, 1), null),
                AcceptanceEntry(c, LocalDate.of(2026, 6, 1), null),
            ),
        )

    fun d(
        y: Int,
        m: Int,
        day: Int,
    ): LocalDate = LocalDate.of(y, m, day)
}
