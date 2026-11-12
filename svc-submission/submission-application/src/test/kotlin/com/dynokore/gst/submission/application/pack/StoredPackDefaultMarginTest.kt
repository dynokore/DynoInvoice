package com.dynokore.gst.submission.application.pack

import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** Cases of INV-AC-20785 over a fake reader that returns every row it holds, whatever date is asked. */
class StoredPackDefaultMarginTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000020"))

    private fun row(
        version: String,
        from: String,
        value: Long?,
    ) = CalendarActivation(
        "P-CAL",
        version,
        LocalDate.parse(from),
        value?.let { PackMargin.of(it, "DAYS") },
    )

    private val v1 = row("1", "2026-01-01", 5)
    private val v2 = row("2", "2026-07-01", 3)
    private val v3 = row("3", "2027-01-01", 2)
    private val v4 = row("4", "2026-07-01", 4)
    private val v5 = row("5", "2026-09-01", null)

    private fun ask(
        date: String,
        vararg rows: CalendarActivation,
    ): PackMarginAnswer = StoredPackDefaultMargin { _, _ -> rows.toList() }.on(tenant, LocalDate.parse(date))

    private fun known(value: Long) = PackMarginAnswer.Known(PackMargin.of(value, "DAYS")!!)

    @Test
    fun `INV-AC-20785 the version in force on the date supplies the margin`() {
        ask("2026-06-30", v1, v2, v3) shouldBe known(5)
        ask("2026-07-01", v1, v2, v3) shouldBe known(3)
        ask("2026-12-31", v1, v2, v3) shouldBe known(3)
    }

    @Test
    fun `INV-AC-20785 nothing in force or an empty reader is unknown`() {
        ask("2025-12-31", v1, v2, v3) shouldBe PackMarginAnswer.Unknown
        ask("2026-07-01") shouldBe PackMarginAnswer.Unknown
    }

    @Test
    fun `INV-AC-20785 a version in force without margin is unknown and no older margin is used`() {
        ask("2026-09-30", v1, v2, v3, v5) shouldBe PackMarginAnswer.Unknown
    }

    @Test
    fun `INV-AC-20785 two versions on the latest date are unknown`() {
        ask("2026-07-01", v1, v2, v3, v4) shouldBe PackMarginAnswer.Unknown
    }

    @Test
    fun `INV-AC-20785 a row dated after the asked date is ignored even if the reader returns it`() {
        ask("2026-06-30", v3) shouldBe PackMarginAnswer.Unknown
        ask("2026-06-30", v1, v3) shouldBe known(5)
    }

    @Test
    fun `a repeated identical row is not a tie`() {
        ask("2026-07-01", v2, v2) shouldBe known(3)
    }
}
