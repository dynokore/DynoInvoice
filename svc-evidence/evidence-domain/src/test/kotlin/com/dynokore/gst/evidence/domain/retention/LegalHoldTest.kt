package com.dynokore.gst.evidence.domain.retention

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class LegalHoldTest {
    private val p = LocalDate.parse("2026-03-01")
    private val hold = LegalHold("H1", "E1", p, "audit", HoldStatus.ACTIVE)

    @Test
    fun `INV-AC-22963 an active hold covers its own entity and period only`() {
        hold.covers("E1", p) shouldBe true
        hold.covers("E2", p) shouldBe false
        hold.covers("E1", p.plusDays(1)) shouldBe false
    }

    @Test
    fun `INV-AC-22963 a released hold covers nothing`() {
        hold.copy(status = HoldStatus.RELEASED).covers("E1", p) shouldBe false
        hold.copy(status = HoldStatus.RELEASED).coversPartition("E1", p, p) shouldBe false
    }

    @Test
    fun `INV-AC-22963 a hold covers a partition when its period lies in the range and the entity is equal`() {
        hold.coversPartition("E1", p.minusDays(5), p.plusDays(5)) shouldBe true
        hold.coversPartition("E1", p, p) shouldBe true
        hold.coversPartition("E1", p.plusDays(1), p.plusDays(9)) shouldBe false
        hold.coversPartition("E1", p.minusDays(9), p.minusDays(1)) shouldBe false
        hold.coversPartition("E1x", p.minusDays(5), p.plusDays(5)) shouldBe false
    }
}
