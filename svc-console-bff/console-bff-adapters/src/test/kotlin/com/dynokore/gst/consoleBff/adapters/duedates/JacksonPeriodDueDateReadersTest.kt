package com.dynokore.gst.consoleBff.adapters.duedates

import com.dynokore.gst.consoleBff.application.duedates.DueDateChange
import com.dynokore.gst.consoleBff.application.duedates.InitialDueDates
import com.dynokore.gst.consoleBff.application.duedates.PeriodKey
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class JacksonPeriodDueDateReadersTest {
    private val change = JacksonPeriodDueDateChangedReader()
    private val records = JacksonPeriodRecordsChangedReader()

    private fun changeJson(
        entity: String = "\"entityRef\":\"E1\"",
        period: String = "\"period\":\"2026-Q4\"",
        date: String = "\"newFilingDueDate\":\"2027-01-31\"",
        version: String = "\"dueDateVersion\":3",
    ) = listOf(
        entity,
        period,
        "\"oldFilingDueDate\":\"2026-12-31\"",
        date,
        "\"cause\":\"X\"",
        version,
        "\"extra\":1",
    ).filter { it.isNotEmpty() }.joinToString(",", "{", "}")

    @Test
    fun `INV-AC-21077 change reader returns the facts of a valid payload and reads period not periodRef`() {
        change.read(changeJson().replace("\"extra\":1", "\"periodRef\":\"WRONG\"")) shouldBe
            DueDateChange(PeriodKey("E1", "2026-Q4"), LocalDate.parse("2027-01-31"), 3L)
    }

    @Test
    fun `INV-AC-21077 change reader rejects every invalid payload`() {
        val invalid =
            listOf(
                "not json",
                "[]",
                "null",
                "\"x\"",
                changeJson(entity = ""),
                changeJson(entity = "\"entityRef\":\"\""),
                changeJson(entity = "\"entityRef\":\" \""),
                changeJson(entity = "\"entityRef\":1"),
                changeJson(period = ""),
                changeJson(period = "\"period\":\"\""),
                changeJson(period = "\"period\":null"),
                changeJson(date = ""),
                changeJson(date = "\"newFilingDueDate\":null"),
                changeJson(date = "\"newFilingDueDate\":\"2027-02-30\""),
                changeJson(date = "\"newFilingDueDate\":\"31/01/2027\""),
                changeJson(date = "\"newFilingDueDate\":20270131"),
                changeJson(version = ""),
                changeJson(version = "\"dueDateVersion\":null"),
                changeJson(version = "\"dueDateVersion\":0"),
                changeJson(version = "\"dueDateVersion\":-1"),
                changeJson(version = "\"dueDateVersion\":1.5"),
                changeJson(version = "\"dueDateVersion\":\"3\""),
                changeJson(version = "\"dueDateVersion\":99999999999999999999"),
            )
        invalid.forEach { shouldThrow<ContractViolation> { change.read(it) } }
    }

    @Test
    fun `INV-AC-21077 records reader returns dated periods and ignores an absent filingDueDate`() {
        val json =
            "{\"entityRef\":\"E1\",\"version\":4,\"periods\":[" +
                "{\"periodRef\":\"P1\",\"periodStart\":\"2026-01-01\",\"periodEnd\":\"2026-03-31\"," +
                "\"filingDueDate\":\"2026-04-30\"}," +
                "{\"periodRef\":\"P2\",\"periodStart\":\"2026-04-01\",\"periodEnd\":\"2026-06-30\"}]}"
        records.read(json) shouldBe InitialDueDates("E1", mapOf("P1" to LocalDate.parse("2026-04-30")))
    }

    @Test
    fun `INV-AC-21077 records reader accepts an empty periods array`() {
        records.read("{\"entityRef\":\"E1\",\"version\":1,\"periods\":[]}") shouldBe InitialDueDates("E1", emptyMap())
    }

    @Test
    fun `INV-AC-21077 records reader rejects every invalid payload`() {
        fun item(extra: String = "\"filingDueDate\":\"2026-04-30\"") =
            "{\"periodRef\":\"P1\"${if (extra.isEmpty()) "" else ",$extra"}}"

        fun doc(
            entity: String = "\"entityRef\":\"E1\"",
            periods: String = "\"periods\":[${item()}]",
        ) = listOf(entity, periods).filter { it.isNotEmpty() }.joinToString(",", "{", "}")
        val invalid =
            listOf(
                "not json",
                "[]",
                "null",
                doc(entity = ""),
                doc(entity = "\"entityRef\":\"\""),
                doc(entity = "\"entityRef\":2"),
                doc(periods = ""),
                doc(periods = "\"periods\":null"),
                doc(periods = "\"periods\":{}"),
                doc(periods = "\"periods\":[1]"),
                doc(periods = "\"periods\":[{\"filingDueDate\":\"2026-04-30\"}]"),
                doc(periods = "\"periods\":[{\"periodRef\":\"\",\"filingDueDate\":\"2026-04-30\"}]"),
                doc(periods = "\"periods\":[${item("\"filingDueDate\":\"2026-13-01\"")}]"),
                doc(periods = "\"periods\":[${item("\"filingDueDate\":null")}]"),
                doc(periods = "\"periods\":[${item("\"filingDueDate\":5")}]"),
            )
        invalid.forEach { shouldThrow<ContractViolation> { records.read(it) } }
    }
}
