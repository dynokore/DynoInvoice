package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.link.ClientSignatoryConsentCheck
import com.dynokore.gst.access.application.link.IssueSignedLinkUseCase
import com.dynokore.gst.access.application.link.LinkPasscodeSetting
import com.dynokore.gst.access.application.link.PasscodeDigests
import com.dynokore.gst.access.application.link.SecureLinkSecrets
import com.dynokore.gst.access.application.link.SignedLinks
import com.dynokore.gst.access.domain.consent.Consent
import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.consent.ConsentStatus
import com.dynokore.gst.access.domain.consent.ConsentTerms
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.link.LinkRef
import com.dynokore.gst.access.domain.link.SignedLink
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.platform.outboxandqueue.EntityRef as EnvelopeEntity

/**
 * INV-T-17212 (INV-REQ-899, rulings INV-Q-17416 B, INV-Q-17431, INV-Q-17030 A): the consumer of SignedLinkRequested for
 * client signatories, and the outcome event it writes for every consumed event. The use case runs on in-memory ports;
 * the outbox is the real mapper over a capturing writer; the transaction fake rolls store and outbox back together.
 */
class SignedLinkOutcomeTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val mapper = JsonMapper.builder().build()
    private val captured = CapturedEvents()
    private val stored = linkedMapOf<String, SignedLink>()
    private val now = Instant.parse("2026-11-09T02:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private var setting = LinkPasscodeSetting.DEFAULT
    private var gatewayBound = false
    private val tenants =
        object : TenantContext {
            override fun current() = tenant

            override fun currentActor() = "system:access-svc"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                val storeBefore = LinkedHashMap(stored)
                val outboxBefore = captured.appended.size
                try {
                    return block()
                } catch (e: RuntimeException) {
                    stored.clear()
                    stored.putAll(storeBefore)
                    while (captured.appended.size > outboxBefore) captured.appended.removeLast()
                    throw e
                }
            }
        }
    private val links =
        object : SignedLinks {
            override fun find(ref: LinkRef) = stored[ref.value]

            override fun lock(ref: LinkRef) = stored[ref.value]

            override fun findBySourceEvent(eventId: String) = stored.values.firstOrNull { it.sourceEventId == eventId }

            override fun insert(link: SignedLink) {
                stored[link.ref.value] = link
            }

            override fun update(
                before: SignedLink,
                after: SignedLink,
            ) {
                stored[after.ref.value] = after
            }
        }
    private val digests =
        object : PasscodeDigests {
            override fun digestOf(
                ref: LinkRef,
                passcode: String,
            ) = "${ref.value}:$passcode"

            override fun matches(
                ref: LinkRef,
                passcode: String,
                digest: String,
            ) = digestOf(ref, passcode) == digest
        }
    private val outbox = OutboxLinkMessages(captured, tenants, mapper)
    private val grantsOut =
        ClientSignatoryGrantOutbox(
            captured,
            tenants,
            mapper,
            InMemoryAuthorityHead(),
            InMemoryAuthorityIncarnation(),
        ) { 1L }
    private val issue =
        IssueSignedLinkUseCase(
            tx,
            links,
            SecureLinkSecrets(),
            digests,
            { _, _ -> "sealed" },
            { setting },
            { gatewayBound },
            outbox,
            grantsOut,
            object : GrantAudit {
                override fun record(entry: GrantAuditEntry) = Unit
            },
            "system:access-svc",
            clock,
        )
    private val consentsOf = { map: Map<String, Consent> -> RecordingConsents(map) }
    private var consents = consentsOf(mapOf("CON-1" to consent("CON-1", "CLIENT-1", ConsentStatus.LIVE)))
    private val handler
        get() =
            SignedLinkRequestedHandler(
                issue,
                outbox,
                mapper,
                setOf("masterdata-svc"),
                ClientSignatoryConsentCheck(consents, clock),
                clock,
            )

    private fun consent(
        ref: String,
        client: String,
        status: ConsentStatus,
    ) = Consent(
        ConsentRef(ref),
        EntityRef(client),
        IdentityRef("agent-1"),
        ConsentTerms(setOf(ScopeItem.SUBMIT_INVOICE_DATA), LocalDate.of(2026, 11, 1), "s@client.example"),
        status,
        if (status == ConsentStatus.WITHDRAWN) "HS-1" else null,
        0,
    )

    private fun payload(
        extra: String = "",
        mobile: String? = "+6591234567",
        email: String? = "a@client.example",
        expiry: String? = now.plusSeconds(3600).toString(),
        subject: String = "SUBJ-1",
    ): String {
        val recipient =
            listOfNotNull(email?.let { """"email":"$it"""" }, mobile?.let { """"mobileNumber":"$it"""" })
                .joinToString(",")
        val parts =
            listOfNotNull(
                """"purpose":"CONFIRM_FILING"""",
                """"subjectReference":"$subject"""",
                """"recipient":{$recipient}""",
                expiry?.let { """"expiry":"$it"""" },
            )
        return "{" + parts.joinToString(",") + extra + "}"
    }

    private fun signatory(
        consentRef: String? = "CON-1",
        clientRef: String? = "CLIENT-1",
    ) = payload(
        ""","linkKind":"clientSignatory"""" +
            consentRef?.let { ""","consentRef":"$it"""" }.orEmpty() +
            clientRef?.let { ""","clientRef":"$it"""" }.orEmpty(),
    )

    private fun envelope(
        data: String,
        id: String = UUID.randomUUID().toString(),
    ) = Envelope(
        id = EventId(id),
        source = "/gst/masterdata-svc",
        type = SignedLinkRequestedHandler.TYPE,
        subject = "signed-link",
        time = now,
        tenant = tenant,
        entity = EnvelopeEntity("ENV-1"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "masterdata-svc-0",
        correlation = CorrelationId("c-$id"),
        causation = CausationId("c-$id"),
        dataJson = data,
    )

    private fun outcomes() =
        captured.appended
            .filter { it.event.type == SignedLinkOutcomeOutbox.TYPE }
            .map { mapper.readTree(it.event.dataJson) }

    private fun messages() = captured.appended.count { it.event.type == OutboxLinkMessages.EVENT_TYPE }

    private fun refusedWith(
        data: String,
        reason: String,
        id: String = UUID.randomUUID().toString(),
    ) {
        handler.handle(envelope(data, id))
        val outcome = outcomes().single()
        assertEquals("REFUSED", outcome.get("outcome").asString())
        assertEquals(reason, outcome.get("reasonCode").asString())
        assertEquals(id, outcome.get("requestEventId").asString())
        assertEquals("SUBJ-1", outcome.get("subjectReference").asString())
        assertTrue(stored.isEmpty())
        assertEquals(0, messages())
    }

    @Test
    fun `INV-AC-2687 a clientSignatory event without consentRef is refused and stores no link`() =
        refusedWith(signatory(consentRef = null), "CONSENT_NOT_LIVE")

    @Test
    fun `INV-AC-2687 a clientSignatory event without clientRef is refused and stores no link`() =
        refusedWith(signatory(clientRef = null), "CONSENT_NOT_LIVE")

    @Test
    fun `INV-AC-2687 an unknown consent is refused with CONSENT_NOT_LIVE and stores no link`() =
        refusedWith(signatory(consentRef = "CON-9"), "CONSENT_NOT_LIVE")

    @Test
    fun `INV-AC-2687 a consent of another client entity is refused with CONSENT_NOT_LIVE and stores no link`() =
        refusedWith(signatory(clientRef = "CLIENT-2"), "CONSENT_NOT_LIVE")

    @Test
    fun `INV-AC-2687 a withdrawn consent is refused with CONSENT_NOT_LIVE and stores no link`() {
        consents = consentsOf(mapOf("CON-1" to consent("CON-1", "CLIENT-1", ConsentStatus.WITHDRAWN)))
        refusedWith(signatory(), "CONSENT_NOT_LIVE")
    }

    @Test
    fun `INV-AC-2687 a consent reference longer than the contract allows is a contract violation with no link`() {
        assertThrows(ContractViolation::class.java) {
            handler.handle(envelope(signatory(consentRef = "C".repeat(65))))
        }
        assertTrue(stored.isEmpty())
        assertEquals(0, captured.appended.size)
    }

    @Test
    fun `a clientSignatory event with a valid pair stores a link carrying both and one ISSUED outcome`() {
        val id = UUID.randomUUID().toString()
        handler.handle(envelope(signatory(), id))
        val link = stored.values.single()
        assertEquals("CON-1", link.consent?.value)
        assertEquals("CLIENT-1", link.client?.value)
        assertEquals("CLIENT-1", link.alertEntity.value)
        val outcome = outcomes().single()
        assertEquals("ISSUED", outcome.get("outcome").asString())
        assertNull(outcome.get("reasonCode"))
        assertEquals(id, outcome.get("requestEventId").asString())
        assertEquals(1, consents.calls)
    }

    @Test
    fun `an event without linkKind and an event with counterparty carry no consent and read none`() {
        handler.handle(envelope(payload()))
        handler.handle(envelope(payload(""","linkKind":"counterparty"""")))
        assertEquals(0, consents.calls)
        assertEquals(2, stored.size)
        assertTrue(stored.values.all { it.consent == null && it.client == null })
        assertEquals(2, outcomes().size)
    }

    @Test
    fun `an unknown linkKind is a contract violation with no link and no outcome`() {
        assertThrows(ContractViolation::class.java) {
            handler.handle(envelope(payload(""","linkKind":"other"""")))
        }
        assertTrue(stored.isEmpty())
        assertEquals(0, captured.appended.size)
    }

    @Test
    fun `INV-AC-2766 an SMS-only installation with no mobile number refuses with MOBILE_NUMBER_REQUIRED and nothing else`() {
        setting = LinkPasscodeSetting(5, smsOnly = true)
        gatewayBound = true
        refusedWith(payload(mobile = null), "MOBILE_NUMBER_REQUIRED")
        assertEquals(0, captured.appended.count { it.event.type != SignedLinkOutcomeOutbox.TYPE })
    }

    @Test
    fun `INV-AC-2766 an SMS-only installation without a bound gateway refuses with MOBILE_NUMBER_REQUIRED`() {
        setting = LinkPasscodeSetting(5, smsOnly = true)
        gatewayBound = false
        refusedWith(payload(), "MOBILE_NUMBER_REQUIRED")
        assertEquals(0, captured.appended.count { it.event.type != SignedLinkOutcomeOutbox.TYPE })
    }

    @Test
    fun `INV-AC-2766 an SMS-only refusal leaves no link, no MessageRequested row and no e-mail passcode`() {
        setting = LinkPasscodeSetting(5, smsOnly = true)
        handler.handle(envelope(payload(mobile = null)))
        assertEquals(0, stored.size)
        assertEquals(0, messages())
        assertEquals(1, outcomes().size)
        assertEquals("MOBILE_NUMBER_REQUIRED", outcomes().single().get("reasonCode").asString())
    }

    @Test
    fun `a recipient without an e-mail address is refused with RECIPIENT_INVALID`() =
        refusedWith(payload(email = null), "RECIPIENT_INVALID")

    @Test
    fun `a blank recipient e-mail is refused with RECIPIENT_INVALID`() {
        handler.handle(envelope(payload(email = " ")))
        assertEquals("RECIPIENT_INVALID", outcomes().single().get("reasonCode").asString())
        assertTrue(stored.isEmpty())
    }

    @Test
    fun `a missing expiry and an unparsable expiry are refused with EXPIRY_INVALID`() {
        refusedWith(payload(expiry = null), "EXPIRY_INVALID")
        captured.appended.clear()
        refusedWith(payload(expiry = "tomorrow"), "EXPIRY_INVALID")
    }

    @Test
    fun `an expiry in the past is refused with EXPIRY_INVALID`() =
        refusedWith(payload(expiry = now.minusSeconds(60).toString()), "EXPIRY_INVALID")

    @Test
    fun `an issued link gives one ISSUED outcome that validates against the contract schema`() {
        val id = UUID.randomUUID().toString()
        handler.handle(envelope(payload(), id))
        val row = captured.appended.single { it.event.type == SignedLinkOutcomeOutbox.TYPE }
        assertEquals("ISSUED", mapper.readTree(row.event.dataJson).get("outcome").asString())
        assertEquals(setOf("requestEventId", "subjectReference", "outcome", "occurredAt"), properties(row.event.dataJson))
        assertEquals(now, Instant.parse(mapper.readTree(row.event.dataJson).get("occurredAt").asString()))
        val schema =
            checkNotNull(
                javaClass.classLoader.getResourceAsStream(
                    "gst/contracts/access/schemas/signed-link-outcome-recorded-payload.schema.json",
                ),
            ).use { it.readBytes().toString(Charsets.UTF_8) }
        val contract = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        val violations = contract.getSchema(schema).validate(row.event.dataJson, InputFormat.JSON)
        assertTrue(violations.isEmpty(), violations.toString())
        assertTrue(!row.event.dataJson.contains("LNK-") && !row.event.dataJson.contains("a@client.example"))
    }

    @Test
    fun `a refusal payload carries a reasonCode and validates against the contract schema`() {
        setting = LinkPasscodeSetting(5, smsOnly = true)
        handler.handle(envelope(payload(mobile = null)))
        val json = captured.appended.single { it.event.type == SignedLinkOutcomeOutbox.TYPE }.event.dataJson
        val schema =
            checkNotNull(
                javaClass.classLoader.getResourceAsStream(
                    "gst/contracts/access/schemas/signed-link-outcome-recorded-payload.schema.json",
                ),
            ).use { it.readBytes().toString(Charsets.UTF_8) }
        val contract = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        assertTrue(contract.getSchema(schema).validate(json, InputFormat.JSON).isEmpty())
        assertEquals(
            setOf("requestEventId", "subjectReference", "outcome", "reasonCode", "occurredAt"),
            properties(json),
        )
    }

    @Test
    fun `the same issued event delivered twice gives one link and one outcome`() {
        val event = envelope(payload())
        handler.handle(event)
        handler.handle(event)
        assertEquals(1, stored.size)
        assertEquals(1, outcomes().size)
    }

    @Test
    fun `a payload without a CloudEvents id gives no link and no outcome`() {
        assertThrows(ContractViolation::class.java) { handler.handle(envelope(payload(), id = "x".repeat(129))) }
        assertTrue(stored.isEmpty())
        assertEquals(0, captured.appended.size)
    }

    @Test
    fun `a payload without a usable subjectReference gives no link and no outcome`() {
        assertThrows(ContractViolation::class.java) { handler.handle(envelope(payload(subject = ""))) }
        assertTrue(stored.isEmpty())
        assertEquals(0, captured.appended.size)
    }

    @Test
    fun `a failure writing the outcome rolls the link back with it`() {
        val failing =
            object : com.dynokore.platform.outboxandqueue.OutboxWriter {
                override fun append(
                    events: List<com.dynokore.platform.outboxandqueue.OutboundEvent>,
                    context: com.dynokore.platform.outboxandqueue.EventContext,
                ): List<Envelope> {
                    check(events.none { it.type == SignedLinkOutcomeOutbox.TYPE }) { "injected outcome failure" }
                    return captured.append(events, context)
                }
            }
        val h =
            SignedLinkRequestedHandler(
                issue,
                OutboxLinkMessages(failing, tenants, mapper),
                mapper,
                setOf("masterdata-svc"),
                ClientSignatoryConsentCheck(consents, clock),
                clock,
            )
        val outer = { h.handle(envelope(payload())) }
        assertThrows(IllegalStateException::class.java) { tx.inTransaction(outer) }
        assertEquals(0, stored.size)
        assertEquals(0, captured.appended.size)
    }

    private fun properties(json: String) = mapper.readTree(json).propertyNames().toSet()
}
