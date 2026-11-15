package com.dynokore.gst.submission.application.typing

import com.dynokore.gst.submission.application.register.CanonicalVersionCreatedData
import com.dynokore.gst.submission.application.register.CanonicalVersionCreatedDataReader
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.DocUuid
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The type-deciding wrapper of the register handler with a recording port (INV-AC-22928). */
class DecideSubmissionTypeTest {
    private val doc = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    private val staged = mutableListOf<TypeDecidedFacts>()

    private class Fail : RuntimeException("delegate failed")

    private fun handler(
        outcome: HandleOutcome?,
        payload: Map<String, Any?>,
    ): TypeDecidingHandler {
        val delegate =
            object : EventHandler {
                override fun handle(event: Envelope): HandleOutcome = outcome ?: throw Fail()
            }
        val reader =
            CanonicalVersionCreatedDataReader {
                CanonicalVersionCreatedData(doc, 1, LocalDate.parse("2026-03-20"), "2026-Q1", "SUBMITTABLE", payload)
            }
        return TypeDecidingHandler(delegate, reader, DecideSubmissionType { staged += it })
    }

    private fun facts(vararg members: Pair<String, Any?>): Map<String, Any?> = mapOf("routingFacts" to mapOf(*members))

    private fun envelope() =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/canonical-svc",
            type = "com.dynokore.gst.canonical.CanonicalVersionCreated.v1",
            subject = doc,
            time = Instant.parse("2026-10-19T08:00:00Z"),
            tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000016")),
            entity = EntityRef("E-001"),
            docUuid = DocUuid(UUID.fromString(doc)),
            transactionDate = LocalDate.parse("2026-03-20"),
            docSequence = 1,
            instanceId = "canonical-svc-0",
            correlation = CorrelationId("corr-1"),
            causation = CausationId("cause-1"),
            dataJson = "{}",
        )

    @Test
    fun `INV-AC-22928 sale off the network is type 2`() {
        val h = handler(HandleOutcome.APPLIED, facts("direction" to "SALE", "networkPath" to "NOT_ON_NETWORK"))
        h.handle(envelope()) shouldBe HandleOutcome.APPLIED
        staged shouldBe listOf(TypeDecidedFacts(doc, 1, "E-001", "TYPE_2"))
    }

    @Test
    fun `INV-AC-22928 purchase off the network is type 3`() {
        handler(
            HandleOutcome.APPLIED,
            facts("direction" to "PURCHASE", "networkPath" to "NOT_ON_NETWORK"),
        ).handle(envelope())
        staged shouldBe listOf(TypeDecidedFacts(doc, 1, "E-001", "TYPE_3"))
    }

    @Test
    fun `INV-AC-22928 missing routing facts stage nothing`() {
        handler(HandleOutcome.APPLIED, emptyMap()).handle(envelope()) shouldBe HandleOutcome.APPLIED
        staged shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22928 debit note on the network stages nothing`() {
        val payload = facts("direction" to "SALE", "networkPath" to "ISSUED_VIA_NETWORK", "debitNote" to true)
        handler(HandleOutcome.APPLIED, payload).handle(envelope())
        staged shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22928 unknown network path is a contract violation`() {
        val payload = facts("direction" to "SALE", "networkPath" to "BOGUS")
        shouldThrow<ContractViolation> { handler(HandleOutcome.APPLIED, payload).handle(envelope()) }
        staged shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22928 ignored delegate stages nothing`() {
        val h = handler(HandleOutcome.IGNORED, facts("direction" to "SALE", "networkPath" to "NOT_ON_NETWORK"))
        h.handle(envelope()) shouldBe HandleOutcome.IGNORED
        staged shouldBe emptyList()
    }

    @Test
    fun `INV-AC-22928 delegate failure propagates`() {
        val h = handler(null, facts("direction" to "SALE", "networkPath" to "NOT_ON_NETWORK"))
        shouldThrow<Fail> { h.handle(envelope()) }
        staged shouldBe emptyList()
    }
}
