package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonPeriodRecordsChangedDataReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The PeriodRecordsChanged reader (INV-AC-20678). */
class JacksonPeriodRecordsChangedDataReaderTest {
    private val reader = JacksonPeriodRecordsChangedDataReader()

    private fun period(
        ref: String? = "\"Q1\"",
        start: String? = "\"2026-01-01\"",
        end: String? = "\"2026-03-31\"",
    ) = buildString {
        append("{")
        val members =
            listOfNotNull(
                ref?.let { "\"periodRef\":$it" },
                start?.let { "\"periodStart\":$it" },
                end?.let { "\"periodEnd\":$it" },
            )
        append(members.joinToString(","))
        append("}")
    }

    private fun json(
        version: String? = "2",
        periods: String? = "[${period()}]",
        extra: String = "",
    ) = buildString {
        append("{\"entityRef\":\"E-001\"")
        if (version != null) append(",\"version\":$version")
        if (periods != null) append(",\"periods\":$periods")
        append(extra)
        append("}")
    }

    @Test
    fun `INV-AC-20678 a valid payload is read`() {
        val d = reader.read(json(periods = "[${period()},${period("\"Q2\"", "\"2026-04-01\"", "\"2026-06-30\"")}]"))
        d.entityRef shouldBe "E-001"
        d.version shouldBe 2
        d.periods.map { it.periodRef } shouldBe listOf("Q1", "Q2")
        d.periods[0].periodStart shouldBe LocalDate.of(2026, 1, 1)
        d.periods[1].periodEnd shouldBe LocalDate.of(2026, 6, 30)
    }

    @Test
    fun `INV-AC-20678 additional members are ignored`() {
        val p = period().dropLast(1) + ",\"filingDueDate\":\"2026-04-30\",\"other\":{}}"
        val d = reader.read(json(periods = "[$p]", extra = ",\"future\":1"))
        d.periods.single().periodRef shouldBe "Q1"
    }

    @Test
    fun `INV-AC-20678 a missing periods member is a ContractViolation`() {
        shouldThrow<ContractViolation> { reader.read(json(periods = null)) }
        shouldThrow<ContractViolation> { reader.read(json(periods = "{}")) }
    }

    @Test
    fun `INV-AC-20678 a period without periodEnd, periodStart or periodRef is a ContractViolation`() {
        shouldThrow<ContractViolation> { reader.read(json(periods = "[${period(end = null)}]")) }
        shouldThrow<ContractViolation> { reader.read(json(periods = "[${period(start = null)}]")) }
        shouldThrow<ContractViolation> { reader.read(json(periods = "[${period(ref = null)}]")) }
        shouldThrow<ContractViolation> { reader.read(json(periods = "[1]")) }
    }

    @Test
    fun `INV-AC-20678 a value that is not an ISO date is a ContractViolation without the value`() {
        listOf("\"2026-13-01\"", "\"SECRET-X\"", "20260101", "null").forEach { v ->
            val e = shouldThrow<ContractViolation> { reader.read(json(periods = "[${period(start = v)}]")) }
            e.message.orEmpty().contains("SECRET-X") shouldBe false
            e.message.orEmpty().contains("2026-13-01") shouldBe false
        }
    }

    @Test
    fun `INV-AC-20678 a missing, zero, negative, string or decimal version is a ContractViolation`() {
        listOf(null, "0", "-1", "\"2\"", "2.5", "null", "3000000000").forEach { v ->
            shouldThrow<ContractViolation> { reader.read(json(version = v)) }
        }
    }

    @Test
    fun `INV-AC-20678 data that is not a JSON object is a ContractViolation`() {
        listOf("not json", "[]", "\"x\"", "").forEach { v ->
            shouldThrow<ContractViolation> { reader.read(v) }
        }
    }
}
