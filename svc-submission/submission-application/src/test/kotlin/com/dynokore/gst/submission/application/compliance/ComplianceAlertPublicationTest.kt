package com.dynokore.gst.submission.application.compliance

import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class ComplianceAlertPublicationTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val day = LocalDate.parse("2026-10-29")

    /** The ledger rule of INV-Q-16751 A: true only when the day is strictly later than the stored one. */
    private class MemoryLedger : AlertEmissionLedger {
        val stored = mutableMapOf<Pair<String, String>, LocalDate>()

        override fun claim(
            tenant: TenantId,
            entityRef: String,
            periodRef: String,
            measuredOn: LocalDate,
        ): Boolean {
            val key = entityRef to periodRef
            val before = stored[key]
            if (before != null && !measuredOn.isAfter(before)) return false
            stored[key] = measuredOn
            return true
        }
    }

    private class RecordingWriter : OutboxWriter {
        val events = mutableListOf<OutboundEvent>()
        val contexts = mutableListOf<EventContext>()

        override fun append(
            events: List<OutboundEvent>,
            context: EventContext,
        ): List<Envelope> {
            this.events += events
            contexts += context
            return emptyList()
        }
    }

    private fun value(
        entity: String = "E-001",
        on: LocalDate = day,
        days: Int? = 3,
    ) = AlertValue(entity, "2026Q1", on, 1, 0, days)

    @Test
    fun `INV-AC-12837 a claimed value writes one event of the contract type with exactly the payload properties`() {
        val writer = RecordingWriter()
        ComplianceAlertPublication.publish(listOf(value()), tenant, MemoryLedger(), writer) shouldBe 1
        writer.events.single().type shouldBe "com.dynokore.gst.submission.ComplianceAlertValuesMeasured.v1"
        writer.events.single().dataJson shouldBe
            """{"entityRef":"E-001","periodRef":"2026Q1","measuredOn":"2026-10-29","documentsWithoutSubmission":1,""" +
            """"documentsPastDue":0,"daysToNearestDeadline":3}"""
        writer.contexts.single().tenant shouldBe tenant
    }

    @Test
    fun `INV-AC-12837 a null days to the nearest deadline is left out of the payload`() {
        val writer = RecordingWriter()
        ComplianceAlertPublication.publish(listOf(value(days = null)), tenant, MemoryLedger(), writer)
        writer.events.single().dataJson shouldBe
            """{"entityRef":"E-001","periodRef":"2026Q1","measuredOn":"2026-10-29","documentsWithoutSubmission":1,""" +
            """"documentsPastDue":0}"""
    }

    @Test
    fun `INV-AC-12837 the same day again writes none and the next day writes one`() {
        val ledger = MemoryLedger()
        val writer = RecordingWriter()
        ComplianceAlertPublication.publish(listOf(value()), tenant, ledger, writer) shouldBe 1
        ComplianceAlertPublication.publish(listOf(value()), tenant, ledger, writer) shouldBe 0
        ComplianceAlertPublication.publish(listOf(value(on = day.minusDays(1))), tenant, ledger, writer) shouldBe 0
        ComplianceAlertPublication.publish(listOf(value(on = day.plusDays(1))), tenant, ledger, writer) shouldBe 1
        writer.events.size shouldBe 2
    }

    @Test
    fun `INV-AC-12837 no value writes no event and a quote in a reference is escaped`() {
        val writer = RecordingWriter()
        ComplianceAlertPublication.publish(emptyList(), tenant, MemoryLedger(), writer) shouldBe 0
        ComplianceAlertPublication.publish(listOf(value(entity = "E\"1\\")), tenant, MemoryLedger(), writer)
        writer.events
            .single()
            .dataJson
            .startsWith("""{"entityRef":"E\"1\\","periodRef"""") shouldBe true
    }
}
