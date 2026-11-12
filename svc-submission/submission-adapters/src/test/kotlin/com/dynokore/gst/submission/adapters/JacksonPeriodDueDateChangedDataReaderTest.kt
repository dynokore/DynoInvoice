package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonPeriodDueDateChangedDataReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The PeriodDueDateChanged reader (INV-AC-20513). */
class JacksonPeriodDueDateChangedDataReaderTest {
    private val reader = JacksonPeriodDueDateChangedDataReader()

    private fun json(
        date: String? = "\"2026-07-31\"",
        version: String? = "2",
        versionName: String = "dueDateVersion",
    ) = buildString {
        append("{\"entityRef\":\"E\",\"period\":\"P\",\"oldFilingDueDate\":\"2026-06-30\",\"cause\":{}")
        if (date != null) append(",\"newFilingDueDate\":$date")
        if (version != null) append(",\"$versionName\":$version")
        append("}")
    }

    @Test
    fun `INV-AC-20513 a valid payload is read`() {
        val d = reader.read(json())
        d.entityRef shouldBe "E"
        d.period shouldBe "P"
        d.newFilingDueDate shouldBe LocalDate.of(2026, 7, 31)
        d.dueDateVersion shouldBe 2L
    }

    @Test
    fun `INV-AC-20513 a missing newFilingDueDate is a ContractViolation`() {
        shouldThrow<ContractViolation> { reader.read(json(date = null)) }
    }

    @Test
    fun `INV-AC-20513 a non-date newFilingDueDate is a ContractViolation without the value`() {
        val e = shouldThrow<ContractViolation> { reader.read(json(date = "\"SECRET-X\"")) }
        (e.message ?: "").contains("SECRET-X") shouldBe false
    }

    @Test
    fun `INV-AC-20513 a missing zero string or decimal dueDateVersion is a ContractViolation`() {
        listOf(null, "0", "-1", "\"2\"", "2.5", "null").forEach { v ->
            shouldThrow<ContractViolation> { reader.read(json(version = v)) }
        }
    }

    @Test
    fun `INV-AC-20513 look-alike version member names are not read`() {
        listOf("dueDateVersions", "duedateversion", "DueDateVersion").forEach { n ->
            shouldThrow<ContractViolation> { reader.read(json(versionName = n)) }
        }
    }

    @Test
    fun `INV-AC-20513 non-JSON and non-object data are a ContractViolation`() {
        shouldThrow<ContractViolation> { reader.read("{") }
        shouldThrow<ContractViolation> { reader.read("[]") }
    }
}
