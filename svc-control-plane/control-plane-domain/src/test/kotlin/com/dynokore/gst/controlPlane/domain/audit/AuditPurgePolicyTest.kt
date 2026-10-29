package com.dynokore.gst.controlPlane.domain.audit

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.Period
import java.time.ZoneOffset

class AuditPurgePolicyTest {
    private val now = Instant.parse("2026-10-29T08:30:00Z")

    private fun yearsAgo(years: Long): Instant = now.atOffset(ZoneOffset.UTC).minusYears(years).toInstant()

    private fun floorOf(vararg figures: Period?) = AuditPurgePolicy.floor(figures.toList(), now)

    @Test
    fun `INV-AC-3823 the floor is the maximum of the region figures`() {
        (floorOf(Period.ofYears(5), Period.ofYears(10)) as PurgeFloor.Determined).retention shouldBe Period.ofYears(10)
        (floorOf(Period.ofYears(10), Period.ofYears(7)) as PurgeFloor.Determined).retention shouldBe Period.ofYears(10)
    }

    @Test
    fun `INV-AC-3823 a maximum under 5 years gives a floor of 5 years`() {
        val floor = floorOf(Period.ofYears(2), Period.ofYears(3)) as PurgeFloor.Determined
        floor.retention shouldBe Period.ofYears(5)
        floor.limit shouldBe yearsAgo(5)
    }

    @Test
    fun `INV-AC-3823 one region without a figure makes the floor undeterminable`() {
        floorOf(Period.ofYears(10), null) shouldBe PurgeFloor.Undeterminable
        floorOf(null) shouldBe PurgeFloor.Undeterminable
    }

    @Test
    fun `INV-AC-3823 an empty region list makes the floor undeterminable`() {
        AuditPurgePolicy.floor(emptyList(), now) shouldBe PurgeFloor.Undeterminable
    }

    @Test
    fun `INV-AC-3823 a cut-off exactly at the floor is allowed and one second younger is refused`() {
        val floor = floorOf(Period.ofYears(5), Period.ofYears(10)) as PurgeFloor.Determined
        AuditPurgePolicy.allows(yearsAgo(10), floor) shouldBe true
        AuditPurgePolicy.allows(yearsAgo(10).plusSeconds(1), floor) shouldBe false
        AuditPurgePolicy.allows(yearsAgo(11), floor) shouldBe true
    }

    @Test
    fun `INV-AC-3823 a cut-off 6 years ago is refused under a 10 year floor`() {
        val floor = floorOf(Period.ofYears(5), Period.ofYears(10)) as PurgeFloor.Determined
        AuditPurgePolicy.allows(yearsAgo(6), floor) shouldBe false
    }

    @Test
    fun `INV-AC-3823 periods in different units are compared by the instant they reach back from the call`() {
        val floor = floorOf(Period.ofYears(6), Period.ofDays(2200)) as PurgeFloor.Determined
        floor.retention shouldBe Period.ofDays(2200)
        floor.limit shouldBe now.atOffset(ZoneOffset.UTC).minusDays(2200).toInstant()
    }

    @Test
    fun `INV-AC-3823 five years back from 29 February is the last day of February`() {
        val leapDay = Instant.parse("2028-02-29T00:00:00Z")
        val floor = AuditPurgePolicy.floor(listOf(Period.ofYears(5)), leapDay) as PurgeFloor.Determined
        floor.limit shouldBe Instant.parse("2023-02-28T00:00:00Z")
    }
}
