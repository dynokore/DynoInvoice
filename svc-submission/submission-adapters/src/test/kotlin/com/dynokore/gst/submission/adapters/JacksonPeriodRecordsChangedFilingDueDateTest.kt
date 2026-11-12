package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonPeriodRecordsChangedDataReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The optional `filingDueDate` of each PeriodRecordsChanged period (INV-AC-20795). */
class JacksonPeriodRecordsChangedFilingDueDateTest {
    private val reader = JacksonPeriodRecordsChangedDataReader()

    private fun period(
        ref: String,
        start: String,
        end: String,
        due: String? = null,
    ): String {
        val member = due?.let { ",\"filingDueDate\":$it" }.orEmpty()
        return """{"periodRef":"$ref","periodStart":"$start","periodEnd":"$end"$member}"""
    }

    private fun json(periods: String) = """{"entityRef":"E1","version":5,"periods":[$periods]}"""

    @Test
    fun `INV-AC-20795 the valid payload gives the dates and an absent member gives null`() {
        val d =
            reader.read(
                json(
                    listOf(
                        period("2026-06", "2026-06-01", "2026-06-30", "\"2026-07-20\""),
                        period("2026-07", "2026-07-01", "2026-07-31", "\"2026-08-25\""),
                        period("2026-08", "2026-08-01", "2026-08-31"),
                    ).joinToString(","),
                ),
            )
        d.periods.map { it.filingDueDate } shouldBe
            listOf(LocalDate.of(2026, 7, 20), LocalDate.of(2026, 8, 25), null)
    }

    private fun refused(due: String) {
        shouldThrow<ContractViolation> { reader.read(json(period("2026-06", "2026-06-01", "2026-06-30", due))) }
    }

    @Test
    fun `INV-AC-20795 M1 a string that is not an ISO date is refused`() = refused("\"2026-13-40\"")

    @Test
    fun `INV-AC-20795 M2 an integer is refused`() = refused("20260720")

    @Test
    fun `INV-AC-20795 M3 JSON null is refused`() = refused("null")
}
