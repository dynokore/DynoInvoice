package com.dynokore.gst.rules.domain

import com.dynokore.gst.rules.domain.pack.AcceptanceCalendar
import com.dynokore.gst.rules.domain.pack.SpecAcceptance
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class AcceptanceCalendarTest {
    private val v1 = SpecAcceptance("v1", LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-31"))
    private val v2 = SpecAcceptance("v2", LocalDate.parse("2026-01-01"))
    private val pack = listOf(v1, v2)

    private fun on(date: String) = AcceptanceCalendar.acceptedOn(pack, LocalDate.parse(date))

    @Test
    fun `INV-AC-7967 15 Feb gives v1 and v2 and 15 Apr gives v2 and v3 is in neither`() {
        on("2026-02-15") shouldBe setOf("v1", "v2")
        on("2026-04-15") shouldBe setOf("v2")
        ("v3" in on("2026-02-15")) shouldBe false
        ("v3" in on("2026-04-15")) shouldBe false
    }

    @Test
    fun `INV-AC-1699 15 Feb gives v1 and v2 and 15 Apr gives v2 only`() {
        on("2026-02-15") shouldBe setOf("v1", "v2")
        on("2026-04-15") shouldBe setOf("v2")
    }

    @Test
    fun `INV-AC-7967 accepted_to is inclusive and the day after excludes the version`() {
        on("2026-03-31") shouldBe setOf("v1", "v2")
        on("2026-04-01") shouldBe setOf("v2")
    }

    @Test
    fun `INV-AC-7967 accepted_from is inclusive and the day before excludes the version`() {
        on("2026-01-01") shouldBe setOf("v1", "v2")
        on("2025-12-31") shouldBe emptySet()
        AcceptanceCalendar.acceptedOn(
            listOf(SpecAcceptance("v2", LocalDate.parse("2026-01-02"))),
            LocalDate.parse("2026-01-01"),
        ) shouldBe
            emptySet()
    }

    @Test
    fun `INV-AC-7967 an absent accepted_to is accepted without end and an empty list gives the empty set`() {
        on("2099-12-31") shouldBe setOf("v2")
        AcceptanceCalendar.acceptedOn(emptyList(), LocalDate.parse("2026-02-15")) shouldBe emptySet()
    }
}
