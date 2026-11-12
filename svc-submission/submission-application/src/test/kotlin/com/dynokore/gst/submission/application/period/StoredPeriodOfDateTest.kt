package com.dynokore.gst.submission.application.period

import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class StoredPeriodOfDateTest {
    private val t = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val t2 = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
    private val calls = mutableListOf<Triple<TenantId, String, LocalDate>>()

    private val answers =
        mapOf(
            LocalDate.of(2026, 3, 31) to listOf("P1"),
            LocalDate.of(2026, 4, 1) to listOf("P2"),
            LocalDate.of(2026, 7, 15) to emptyList(),
            LocalDate.of(2026, 5, 1) to listOf("P2", "P2X"),
        )

    private val lookup =
        StoredPeriodOfDate { tenant, entity, date ->
            calls += Triple(tenant, entity, date)
            if (tenant == t && entity == "E-001") answers[date].orEmpty() else emptyList()
        }

    private fun ask(
        tenant: TenantId,
        entity: String,
        date: String,
    ) = lookup.periodOf(tenant, entity, LocalDate.parse(date))

    @Test
    fun `INV-AC-20676 one containing period is known`() {
        ask(t, "E-001", "2026-03-31") shouldBe PeriodAnswer.Known("P1")
        ask(t, "E-001", "2026-04-01") shouldBe PeriodAnswer.Known("P2")
    }

    @Test
    fun `INV-AC-20676 no containing period or more than one is unknown`() {
        ask(t, "E-001", "2026-07-15") shouldBe PeriodAnswer.Unknown
        ask(t, "E-001", "2026-05-01") shouldBe PeriodAnswer.Unknown
    }

    @Test
    fun `INV-AC-20676 another tenant or entity is unknown`() {
        ask(t2, "E-001", "2026-03-31") shouldBe PeriodAnswer.Unknown
        ask(t, "E-009", "2026-03-31") shouldBe PeriodAnswer.Unknown
    }

    @Test
    fun `INV-AC-20676 the reader receives exactly the tenant, entity and date asked`() {
        ask(t2, "E-009", "2026-03-31")
        calls.single() shouldBe Triple(t2, "E-009", LocalDate.of(2026, 3, 31))
    }
}
