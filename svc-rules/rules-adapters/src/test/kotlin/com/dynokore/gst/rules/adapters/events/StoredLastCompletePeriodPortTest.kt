package com.dynokore.gst.rules.adapters.events

import com.dynokore.gst.rules.adapters.persistence.PeriodSpan
import com.dynokore.gst.rules.application.PeriodCalendarUnavailableException
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class StoredLastCompletePeriodPortTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-4000-8000-000000000001"))
    private val entity = "ENT-1"

    // 2026-04-01 in Asia/Singapore, 2026-03-31 in UTC
    private val singaporeApril1 = Clock.fixed(Instant.parse("2026-03-31T16:30:00Z"), ZoneOffset.UTC)

    private fun span(
        ref: String,
        end: String,
    ) = PeriodSpan(ref, LocalDate.parse(end).minusDays(29), LocalDate.parse(end))

    private val a = span("A", "2026-03-30")
    private val b = span("B", "2026-03-31")
    private val c = span("C", "2026-04-01")

    private fun port(
        clock: Clock,
        vararg spans: PeriodSpan,
    ) = StoredLastCompletePeriodPort({ _, _ -> spans.toList() }, clock)

    @Test
    fun `INV-AC-20223 the stored span with the greatest complete periodEnd is the answer`() {
        port(singaporeApril1, c, a, b).lastCompletePeriod(tenant, entity) shouldBe "B"
    }

    @Test
    fun `INV-AC-20223 the span ending on the clock date is not complete`() {
        port(singaporeApril1, c).lastCompletePeriod(tenant, entity) shouldBe null
    }

    @Test
    fun `INV-AC-20223 with a complete span and one ending on the clock date the complete one is the answer`() {
        port(singaporeApril1, a, c).lastCompletePeriod(tenant, entity) shouldBe "A"
    }

    @Test
    fun `INV-AC-20223 the clock date is the Asia Singapore date, never the UTC date`() {
        val utcMarch31 = Clock.fixed(Instant.parse("2026-03-31T15:59:59Z"), ZoneOffset.UTC)
        port(utcMarch31, a, b, c).lastCompletePeriod(tenant, entity) shouldBe "A"
    }

    @Test
    fun `INV-AC-20223 the span with the smallest periodEnd is never chosen`() {
        port(singaporeApril1, a, b).lastCompletePeriod(tenant, entity) shouldBe "B"
        port(singaporeApril1, b, a).lastCompletePeriod(tenant, entity) shouldBe "B"
    }

    @Test
    fun `INV-AC-20223 no stored record is an unavailable period calendar`() {
        val failure =
            shouldThrow<PeriodCalendarUnavailableException> {
                StoredLastCompletePeriodPort({ _, _ -> null }, singaporeApril1).lastCompletePeriod(tenant, entity)
            }
        failure.javaClass shouldBe PeriodCalendarUnavailableException::class.java
        failure.reason shouldContain "period calendar"
    }

    @Test
    fun `INV-AC-20223 stored records with none complete answer null`() {
        port(
            singaporeApril1,
            b.copy(periodEnd = LocalDate.parse("2026-04-02")),
            c,
        ).lastCompletePeriod(tenant, entity) shouldBe
            null
        StoredLastCompletePeriodPort(
            { _, _ -> emptyList() },
            singaporeApril1,
        ).lastCompletePeriod(tenant, entity) shouldBe
            null
    }

    @Test
    fun `INV-AC-20223 a store failure is an unavailable period calendar`() {
        val failure =
            shouldThrow<PeriodCalendarUnavailableException> {
                StoredLastCompletePeriodPort({ _, _ -> throw DataAccessException("down") }, singaporeApril1)
                    .lastCompletePeriod(tenant, entity)
            }
        failure.reason shouldContain "period calendar"
    }
}
