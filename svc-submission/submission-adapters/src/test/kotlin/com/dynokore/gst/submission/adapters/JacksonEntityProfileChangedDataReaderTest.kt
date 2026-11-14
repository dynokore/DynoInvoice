package com.dynokore.gst.submission.adapters

import com.dynokore.gst.submission.adapters.events.JacksonEntityProfileChangedDataReader
import com.dynokore.platform.outboxandqueue.ContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The EntityProfileChanged reader (INV-AC-22202). */
class JacksonEntityProfileChangedDataReaderTest {
    private val reader = JacksonEntityProfileChangedDataReader()

    private fun json(
        entity: String? = "\"E\"",
        effective: String? = "\"2026-06-01\"",
        attribute: String = "obligationStartDate",
        newValue: String = "\"2026-07-01\"",
        changed: String = "[{\"attribute\":\"$attribute\",\"oldValue\":null,\"newValue\":$newValue}]",
    ) = buildString {
        append("{\"changedAttributes\":$changed")
        if (entity != null) append(",\"entityRef\":$entity")
        if (effective != null) append(",\"effectiveDate\":$effective")
        append("}")
    }

    @Test
    fun `INV-AC-22202 a valid payload gives the start date and the effective date`() {
        val d = reader.read(json())
        d.entityRef shouldBe "E"
        d.obligationStartDate shouldBe LocalDate.of(2026, 7, 1)
        d.effectiveDate shouldBe LocalDate.of(2026, 6, 1)
    }

    @Test
    fun `INV-AC-22202 a payload without the attribute gives a null start date`() {
        reader.read(json(attribute = "uen")).obligationStartDate shouldBe null
        reader.read(json(changed = "[]")).obligationStartDate shouldBe null
    }

    @Test
    fun `INV-AC-22202 a look-alike attribute name is not read`() {
        listOf("obligationStartDates", "ObligationStartDate", "obligationstartdate", "obligationStartDate ").forEach {
            reader.read(json(attribute = it)).obligationStartDate shouldBe null
        }
    }

    @Test
    fun `INV-AC-22202 the first matching item is read among several`() {
        val changed =
            "[{\"attribute\":\"uen\",\"newValue\":\"X\"},{\"attribute\":\"obligationStartDate\",\"newValue\":\"2026-08-01\"}," +
                "{\"attribute\":\"obligationStartDate\",\"newValue\":\"2026-09-01\"}]"
        reader.read(json(changed = changed)).obligationStartDate shouldBe LocalDate.of(2026, 8, 1)
    }

    @Test
    fun `INV-AC-22202 a missing entityRef or effectiveDate is a ContractViolation`() {
        shouldThrow<ContractViolation> { reader.read(json(entity = null)) }
        shouldThrow<ContractViolation> { reader.read(json(effective = null)) }
        shouldThrow<ContractViolation> { reader.read(json(effective = "\"SECRET-X\"")) }
    }

    @Test
    fun `INV-AC-22202 a non-date newValue is a ContractViolation without the value`() {
        listOf("\"SECRET-X\"", "null", "20260701", "\"\"").forEach {
            val e = shouldThrow<ContractViolation> { reader.read(json(newValue = it)) }
            e.message.orEmpty().contains("SECRET-X") shouldBe false
        }
    }

    @Test
    fun `INV-AC-22202 data that is not a JSON object is a ContractViolation`() {
        listOf("[]", "\"x\"", "{").forEach { shouldThrow<ContractViolation> { reader.read(it) } }
    }
}
