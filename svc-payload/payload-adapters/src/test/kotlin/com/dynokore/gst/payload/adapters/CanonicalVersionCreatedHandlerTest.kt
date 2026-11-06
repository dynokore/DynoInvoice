package com.dynokore.gst.payload.adapters

import com.dynokore.gst.payload.adapters.events.CanonicalVersionCreatedHandler
import com.dynokore.gst.payload.application.version.RecordVersionFacts
import com.dynokore.gst.payload.application.version.VersionFactsRecorder
import com.dynokore.gst.payload.domain.version.VersionFacts
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CanonicalVersionCreatedHandlerTest {
    private val doc = UUID.fromString("0b1f2c3d-0000-4000-8000-000000000002")

    private class Memory : VersionFactsRecorder {
        val rows = mutableMapOf<Pair<UUID, Int>, VersionFacts>()

        override fun record(
            docUuid: UUID,
            version: Int,
            facts: VersionFacts,
        ): Boolean = rows.putIfAbsent(docUuid to version, facts) == null
    }

    private val memory = Memory()
    private val handler = CanonicalVersionCreatedHandler(RecordVersionFacts(memory))

    private fun event(data: String) =
        Envelope(
            id = EventId("ev-1"),
            source = "/gst/canonical-svc",
            type = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1",
            subject = doc.toString(),
            time = Instant.parse("2026-10-20T10:00:00Z"),
            tenant = TenantId(UUID.randomUUID()),
            entity = EntityRef("E-1"),
            docUuid = DocUuid(doc),
            transactionDate = LocalDate.of(2026, 1, 15),
            docSequence = 1,
            instanceId = "canonical-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = data,
        )

    private fun payload(
        currency: String,
        total: String? = null,
        sgd: String? = null,
        docField: String = """"docUuid":"$doc",""",
    ): String {
        val amounts =
            listOfNotNull(
                total?.let { """"totalIncludingTax":"$it"""" },
                sgd?.let { """"sgdTotalIncludingTax":"$it"""" },
            ).joinToString(",")
        return """{$docField"version":1,"canonicalDocument":{"currency":{"value":"$currency"},""" +
            """"totals":{"value":{$amounts}}}}"""
    }

    @Test
    fun `an SGD event stores the total`() {
        handler.handle(event(payload("SGD", total = "109.00"))) shouldBe HandleOutcome.APPLIED
        memory.rows[doc to 1] shouldBe VersionFacts("E-1", BigDecimal("109.00"))
    }

    @Test
    fun `a USD event with an SGD total stores that total`() {
        handler.handle(event(payload("USD", total = "80.00", sgd = "1000.00"))) shouldBe HandleOutcome.APPLIED
        memory.rows[doc to 1] shouldBe VersionFacts("E-1", BigDecimal("1000.00"))
    }

    @Test
    fun `a USD event without an SGD total stores a null total`() {
        handler.handle(event(payload("USD", total = "80.00"))) shouldBe HandleOutcome.APPLIED
        memory.rows[doc to 1] shouldBe VersionFacts("E-1", null)
    }

    @Test
    fun `a repeat is ignored`() {
        handler.handle(event(payload("SGD", total = "109.00"))) shouldBe HandleOutcome.APPLIED
        handler.handle(event(payload("SGD", total = "1.00"))) shouldBe HandleOutcome.IGNORED
        memory.rows[doc to 1] shouldBe VersionFacts("E-1", BigDecimal("109.00"))
    }

    @Test
    fun `a missing docUuid raises ContractViolation`() {
        shouldThrow<ContractViolation> { handler.handle(event(payload("SGD", total = "1.00", docField = ""))) }
    }

    @Test
    fun `a total 1e3 raises ContractViolation`() {
        shouldThrow<ContractViolation> { handler.handle(event(payload("SGD", total = "1e3"))) }
    }

    @Test
    fun `a payload that is not JSON raises ContractViolation`() {
        shouldThrow<ContractViolation> { handler.handle(event("{not json")) }
    }
}
