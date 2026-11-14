package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.http.FilingBinding
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

class FilingBindingTest {
    private val singapore = ZoneId.of("Asia/Singapore")

    @Test
    fun `INV-AC-20947 an instant in UTC gives the business-zone date`() {
        FilingBinding.toBusinessDate(OffsetDateTime.parse("2026-04-28T20:00:00Z"), singapore) shouldBe
            LocalDate.of(2026, 4, 29)
        FilingBinding.toBusinessDate(OffsetDateTime.parse("2026-04-29T04:00:00+08:00"), singapore) shouldBe
            LocalDate.of(2026, 4, 29)
        FilingBinding.toBusinessDate(OffsetDateTime.parse("2026-04-28T20:00:00Z"), ZoneId.of("UTC")) shouldBe
            LocalDate.of(2026, 4, 28)
    }

    @Test
    fun `INV-AC-20947 the start of a date renders the offset of the zone`() {
        val start = FilingBinding.startOfDay(LocalDate.of(2026, 4, 29), singapore)
        start.toString() shouldBe "2026-04-29T00:00+08:00"
        start.offset.totalSeconds shouldBe 8 * 3600
    }
}
