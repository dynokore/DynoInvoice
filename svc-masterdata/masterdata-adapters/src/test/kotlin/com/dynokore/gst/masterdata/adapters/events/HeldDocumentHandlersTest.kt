package com.dynokore.gst.masterdata.adapters.events

import com.dynokore.gst.masterdata.application.held.HeldDocumentFact
import com.dynokore.gst.masterdata.application.held.HeldDocuments
import com.dynokore.gst.masterdata.application.held.HeldKind
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** INV-T-26224: the handlers hand the hold or released reasons to the held-documents replica (INV-REQ-316). */
class HeldDocumentHandlersTest {
    private val mapper = JsonMapper.builder().build()
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000c1"))
    private val doc = "00000000-0000-0000-0000-00000000000d"
    private val facts = mutableListOf<HeldDocumentFact>()
    private val fake =
        object : HeldDocuments {
            override fun apply(fact: HeldDocumentFact) {
                facts += fact
            }

            override fun heldFor(
                entity: String,
                party: PartyRef,
            ): List<UUID> = emptyList()

            override fun rul011Blocked(entity: String): Set<PartyRef> = emptySet()
        }
    private val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    private fun envelope(
        type: String,
        data: String,
    ) = Envelope(
        id = EventId(UUID.randomUUID().toString()),
        source = "/gst/canonical",
        type = type,
        subject = "held",
        time = Instant.now(),
        tenant = tenant,
        entity = EntityRef("E"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "canonical-svc-0",
        correlation = CorrelationId("c-1"),
        causation = CausationId("c-1"),
        dataJson = data,
    )

    private val held = DocumentHeldHandler(tx, fake, mapper)
    private val released = DocumentReleasedHandler(tx, fake, mapper)

    @Test
    fun `INV-AC-26219 a DocumentHeld hands the fake the hold reasons`() {
        val data =
            """{"docUuid":"$doc","docsequence":1,"entityRef":"E","partyRef":"P","holdReasons":["RUL-011","MDM-002"]}"""
        held.handle(envelope(ConsumedHeldDocumentEvents.DOCUMENT_HELD, data))
        assertEquals(1, facts.size)
        assertEquals(HeldKind.HELD, facts[0].kind)
        assertEquals(setOf("RUL-011", "MDM-002"), facts[0].reasons)
    }

    @Test
    fun `INV-AC-26219 a DocumentReleased hands the fake a RELEASED fact with the released reasons`() {
        val data = """{"docUuid":"$doc","docsequence":2,"releasedHoldReasons":["RUL-011"]}"""
        released.handle(envelope(ConsumedHeldDocumentEvents.DOCUMENT_RELEASED, data))
        assertEquals(HeldKind.RELEASED, facts.single().kind)
        assertEquals(setOf("RUL-011"), facts.single().reasons)
    }

    @Test
    fun `INV-AC-26219 a payload without the required reasons member is still a ContractViolation`() {
        val heldData = """{"docUuid":"$doc","docsequence":1,"entityRef":"E","partyRef":"P"}"""
        assertThrows(ContractViolation::class.java) {
            held.handle(envelope(ConsumedHeldDocumentEvents.DOCUMENT_HELD, heldData))
        }
        val releasedData = """{"docUuid":"$doc","docsequence":2}"""
        assertThrows(ContractViolation::class.java) {
            released.handle(envelope(ConsumedHeldDocumentEvents.DOCUMENT_RELEASED, releasedData))
        }
        assertEquals(0, facts.size)
    }
}
