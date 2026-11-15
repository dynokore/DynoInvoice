package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.application.drain.SafetyMarginAnswer
import com.dynokore.gst.submission.application.pack.PackMargin
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class AcknowledgementDeadlinesTest {
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000022"))
    private val a = Instant.parse("2026-03-01T08:00:00Z")
    private val plusT2 = Instant.parse("2026-03-01T10:00:00Z")
    private val hours48 = SafetyMarginAnswer.Known(PackMargin.of(48, "HOURS")!!)

    private fun service(inputs: AcknowledgementInputs) =
        AcknowledgementDeadlines(inputs, ZoneId.of("Asia/Singapore"), Duration.ofMinutes(120))

    private fun at(basis: AcknowledgementBasis) = service { _, _ -> basis }.at(tenant, "doc", a)

    @Test
    fun `INV-AC-22760 F1 due instant minus margin later than threshold gives accepted plus threshold`() {
        at(AcknowledgementBasis(LocalDate.parse("2026-03-15"), hours48)) shouldBe plusT2
    }

    @Test
    fun `INV-AC-22760 F2 due instant minus margin earlier gives the cap`() {
        at(AcknowledgementBasis(LocalDate.parse("2026-03-02"), hours48)) shouldBe Instant.parse("2026-02-28T16:00:00Z")
    }

    @Test
    fun `INV-AC-22760 F3 null due date gives no cap`() {
        at(AcknowledgementBasis(null, hours48)) shouldBe plusT2
    }

    @Test
    fun `INV-AC-22760 F4 unavailable margin gives no cap`() {
        at(AcknowledgementBasis(LocalDate.parse("2026-03-02"), SafetyMarginAnswer.Unavailable)) shouldBe plusT2
    }

    @Test
    fun `INV-AC-22760 F5 two DAYS margin is two calendar days in the zone`() {
        val days = SafetyMarginAnswer.Known(PackMargin.of(2, "DAYS")!!)
        at(AcknowledgementBasis(LocalDate.parse("2026-03-02"), days)) shouldBe Instant.parse("2026-02-28T16:00:00Z")
    }

    @Test
    fun `INV-AC-22760 F6 a throwing reader gives no cap and does not throw, an Error is not caught`() {
        service { _, _ -> throw IllegalStateException("unreadable") }.at(tenant, "doc", a) shouldBe plusT2
        assertThrows<StackOverflowError> { service { _, _ -> throw StackOverflowError() }.at(tenant, "doc", a) }
    }
}
