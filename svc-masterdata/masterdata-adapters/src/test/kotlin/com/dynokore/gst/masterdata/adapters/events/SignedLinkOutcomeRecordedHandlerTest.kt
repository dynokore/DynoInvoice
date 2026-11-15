package com.dynokore.gst.masterdata.adapters.events

import com.dynokore.gst.masterdata.application.clientsignatory.ApplyLinkOutcome
import com.dynokore.gst.masterdata.application.clientsignatory.ClientSignatories
import com.dynokore.gst.masterdata.application.clientsignatory.ClientSignatory
import com.dynokore.gst.masterdata.application.clientsignatory.ClientSignatoryLinkRequest
import com.dynokore.gst.masterdata.application.clientsignatory.LinkRefusalReason
import com.dynokore.gst.masterdata.application.clientsignatory.LinkState
import com.dynokore.gst.masterdata.application.clientsignatory.LinkStatus
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * INV-T-17443: the SignedLinkRequested payload of a client signatory against the published schema, and the consumer
 * of SignedLinkOutcomeRecorded (INV-Q-17431) over a store that keeps the records in memory.
 */
private const val AT = "2026-11-16T08:00:00Z"

class SignedLinkOutcomeRecordedHandlerTest {
    private val mapper = JsonMapper.builder().build()
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000c1"))

    private fun schema(name: String) =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(
            checkNotNull(javaClass.classLoader.getResourceAsStream("gst/contracts/$name")).use {
                it.readBytes().toString(Charsets.UTF_8)
            },
        )

    private val records = linkedMapOf<String, ClientSignatory>()
    private val store =
        object : ClientSignatories {
            override fun findByClientAndEmail(
                clientRef: String,
                email: String,
            ) = records.values.firstOrNull { it.clientRef == clientRef && it.email == email }

            override fun findByRequestEventId(requestEventId: String) =
                records.values.firstOrNull { it.requestEventId == requestEventId }

            override fun insert(signatory: ClientSignatory) {
                records[signatory.signatoryRef] = signatory
            }

            override fun saveRequestEventId(
                signatoryRef: String,
                requestEventId: String,
            ) {
                records[signatoryRef] = records.getValue(signatoryRef).copy(requestEventId = requestEventId)
            }

            override fun saveLinkStatus(
                signatoryRef: String,
                status: LinkStatus,
            ) {
                records[signatoryRef] = records.getValue(signatoryRef).copy(linkStatus = status)
            }

            override fun nextRef() = "SIG-${records.size + 1}"
        }
    private val handler =
        SignedLinkOutcomeRecordedHandler(
            ApplyLinkOutcome(
                object : TransactionRunner {
                    override fun <T> inTransaction(block: () -> T): T = block()
                },
                store,
            ),
            mapper,
        )

    private fun pending(requestEventId: String = "evt-1") {
        records["SIG-1"] =
            ClientSignatory(
                "SIG-1",
                "CLI-204",
                "Tan",
                "wm.tan@example.com.sg",
                null,
                "CNS-77",
                LinkStatus(LinkState.PENDING),
                requestEventId,
                Instant.parse("2026-11-16T00:00:00Z"),
            )
    }

    private fun envelope(data: String) =
        Envelope(
            id = EventId(UUID.randomUUID().toString()),
            source = "/gst/access",
            type = ConsumedSignedLinkEvents.SIGNED_LINK_OUTCOME_RECORDED,
            subject = "outcome",
            time = Instant.now(),
            tenant = tenant,
            entity = EntityRef("CLI-204"),
            docUuid = null,
            transactionDate = null,
            docSequence = null,
            instanceId = "access-svc-0",
            correlation = CorrelationId("c-1"),
            causation = CausationId("c-1"),
            dataJson = data,
        )

    private fun issued(id: String = "evt-1") =
        """{"requestEventId":"$id","subjectReference":"CNS-77","outcome":"ISSUED","occurredAt":"$AT"}"""

    private fun refused(
        reason: String,
        id: String = "evt-1",
    ) = """{"requestEventId":"$id","subjectReference":"CNS-77","outcome":"REFUSED","reasonCode":"$reason",""" +
        """"occurredAt":"2026-11-16T08:00:00Z"}"""

    @Test
    fun `INV-AC-17444 ISSUED sets ISSUED on the record with that requestEventId`() {
        pending()
        assertEquals(HandleOutcome.APPLIED, handler.handle(envelope(issued())))
        assertEquals(LinkStatus(LinkState.ISSUED), records.getValue("SIG-1").linkStatus)
    }

    @Test
    fun `INV-AC-17444 REFUSED with MOBILE_NUMBER_REQUIRED sets REFUSED with that reason`() {
        pending()
        assertEquals(HandleOutcome.APPLIED, handler.handle(envelope(refused("MOBILE_NUMBER_REQUIRED"))))
        assertEquals(
            LinkStatus(LinkState.REFUSED, LinkRefusalReason.MOBILE_NUMBER_REQUIRED),
            records.getValue("SIG-1").linkStatus,
        )
    }

    @Test
    fun `INV-AC-17444 the same outcome event delivered twice changes nothing the second time`() {
        pending()
        handler.handle(envelope(issued()))
        val before = records.toMap()
        assertEquals(HandleOutcome.IGNORED, handler.handle(envelope(issued())))
        assertEquals(before, records.toMap())
    }

    @Test
    fun `INV-AC-17444 an event for an unknown request id changes no record and is a handled no-op`() {
        pending()
        val before = records.toMap()
        assertEquals(HandleOutcome.IGNORED, handler.handle(envelope(issued("evt-unknown"))))
        assertEquals(before, records.toMap())
    }

    @Test
    fun `INV-AC-17444 a stale outcome never moves an ISSUED record back to PENDING`() {
        pending()
        handler.handle(envelope(issued()))
        assertEquals(HandleOutcome.IGNORED, handler.handle(envelope(refused("CONSENT_NOT_LIVE"))))
        assertEquals(LinkStatus(LinkState.ISSUED), records.getValue("SIG-1").linkStatus)
    }

    @Test
    fun `INV-AC-17444 a payload that breaks the contract is a contract violation and changes nothing`() {
        pending()
        listOf(
            """{"subjectReference":"CNS-77","outcome":"ISSUED","occurredAt":"2026-11-16T08:00:00Z"}""",
            """{"requestEventId":"evt-1","subjectReference":"CNS-77","outcome":"MAYBE","occurredAt":"$AT"}""",
            """{"requestEventId":"evt-1","subjectReference":"CNS-77","outcome":"REFUSED","occurredAt":"$AT"}""",
            refused("mobile_number_required"),
            """{"requestEventId":"evt-1","subjectReference":"CNS-77","outcome":"ISSUED","occurredAt":"yesterday"}""",
        ).forEach { assertThrows(ContractViolation::class.java) { handler.handle(envelope(it)) } }
        assertEquals(LinkStatus(LinkState.PENDING), records.getValue("SIG-1").linkStatus)
    }

    @Test
    fun `INV-AC-17444 the outcome event type and topic are those of the access contract`() {
        assertEquals(
            "gst.access.signed-link-outcome-recorded.v1",
            ConsumedSignedLinkEvents.SUBSCRIPTIONS[ConsumedSignedLinkEvents.SIGNED_LINK_OUTCOME_RECORDED],
        )
        assertEquals("accessSignedLinkOutcomeRecorded", ConsumedSignedLinkEvents.OUTCOME_RECORDED_CHANNEL)
    }

    private val written = mutableListOf<Pair<EventContext, OutboundEvent>>()
    private val outbox =
        ClientSignatoryLinkOutbox(
            object : OutboxWriter {
                override fun append(
                    events: List<OutboundEvent>,
                    context: EventContext,
                ): List<Envelope> {
                    events.forEach { written += context to it }
                    return events.map { envelope(it.dataJson).copy(id = EventId("evt-written")) }
                }
            },
            mapper,
            object : TenantContext {
                override fun current() = tenant

                override fun currentActor(): String? = "test"

                override fun <T> bind(
                    tenant: TenantId,
                    actor: String,
                    block: () -> T,
                ): T = block()
            },
        )

    private val request =
        ClientSignatoryLinkRequest(
            "CLI-204",
            "CNS-77",
            "SIG-31",
            "SIG-31",
            "client-signatory-consent",
            "CNS-77",
            "wm.tan@example.com.sg",
            "+6591234567",
            "EMAIL",
            Instant.parse("2026-12-01T00:00:00Z"),
        )

    @Test
    fun `INV-AC-17444 the signatory SignedLinkRequested payload carries the contract members`() {
        assertEquals("evt-written", outbox.publish(request))
        val (context, event) = written.single()
        assertEquals("com.dynokore.gst.masterdata.SignedLinkRequested.v1", event.type)
        assertEquals("SIG-31", context.aggregateKey)
        val json = mapper.readTree(event.dataJson)
        assertEquals("clientSignatory", json["linkKind"].asString())
        assertEquals("SIG-31", json["sourceRecordId"].asString())
        assertEquals("SIG-31", json["signatoryRef"].asString())
        assertEquals("CLI-204", json["clientRef"].asString())
        assertEquals("CNS-77", json["consentRef"].asString())
        assertEquals("EMAIL", json["channel"].asString())
        assertEquals("+6591234567", json["recipient"]["mobileNumber"].asString())
    }

    @Test
    fun `INV-AC-17444 the signatory SignedLinkRequested payload validates against the schema`() {
        outbox.publish(request)
        val schema = schema("masterdata/schemas/signed-link-requested-payload.schema.json")
        assertTrue(schema.validate(written.single().second.dataJson, InputFormat.JSON).isEmpty())
        val noMobile = outbox.payload(request.copy(mobileNumber = null))
        assertTrue(schema.validate(noMobile.toString(), InputFormat.JSON).isEmpty())
        assertTrue(!noMobile["recipient"].has("mobileNumber"))
    }
}
