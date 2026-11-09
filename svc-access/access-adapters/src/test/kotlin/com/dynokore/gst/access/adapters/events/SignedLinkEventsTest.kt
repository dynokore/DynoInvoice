package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.link.IssueSignedLinkUseCase
import com.dynokore.gst.access.application.link.LinkPasscodeSetting
import com.dynokore.gst.access.application.link.PasscodeDigests
import com.dynokore.gst.access.application.link.SecureLinkSecrets
import com.dynokore.gst.access.application.link.SignedLinks
import com.dynokore.gst.access.application.link.VerifyLinkOutcome
import com.dynokore.gst.access.application.link.VerifySignatoryLinkUseCase
import com.dynokore.gst.access.domain.link.LinkRef
import com.dynokore.gst.access.domain.link.SignedLink
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
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
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-AC-18218: SignedLinkIssued and SignedLinkInvalidated reach the outbox from the signed-link use cases, in the
 * transaction that stores the state they report. The use cases run on in-memory ports; the outbox is the real mapper
 * over a capturing writer; the transaction fake rolls the store and the outbox back together when the block throws.
 */
class SignedLinkEventsTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val mapper = JsonMapper.builder().build()
    private val captured = CapturedEvents()
    private val stored = linkedMapOf<String, SignedLink>()
    private var failAfterStore = false
    private val now = Instant.parse("2026-11-09T02:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
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
                check(stored[before.ref.value] == before) { "stale" }
                stored[after.ref.value] = after
            }
        }
    private val digests =
        object : PasscodeDigests {
            override fun digestOf(
                ref: LinkRef,
                passcode: String,
            ) = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("${ref.value}:$passcode".toByteArray()),
            )

            override fun matches(
                ref: LinkRef,
                passcode: String,
                digest: String,
            ) = digestOf(ref, passcode) == digest
        }
    private val outbox = OutboxLinkMessages(captured, tenants, mapper)
    private val sentPasscodes = mutableListOf<String>()
    private val issue =
        IssueSignedLinkUseCase(
            tx,
            links,
            SecureLinkSecrets(),
            digests,
            { passcode, _ ->
                sentPasscodes += passcode
                "sealed"
            },
            { LinkPasscodeSetting.DEFAULT },
            { false },
            outbox,
            object : GrantAudit {
                override fun record(entry: GrantAuditEntry) {
                    check(!failAfterStore) { "injected failure after the link store" }
                }
            },
            "system:access-svc",
            clock,
        )
    private val verify =
        VerifySignatoryLinkUseCase(
            tx,
            links,
            digests,
            { LinkPasscodeSetting.DEFAULT },
            { _, _ -> },
            SignedLinkEventOutbox(captured, tenants, mapper),
            clock,
        )
    private val handler =
        SignedLinkRequestedHandler(issue, outbox, mapper, setOf("masterdata-svc"))

    private fun payload(
        subject: String? = "R1",
        subjectKey: String = "subjectReference",
        purpose: String = "CONFIRM_FILING",
    ): String {
        val subjectPart = subject?.let { """"$subjectKey":$it,""" }.orEmpty()
        return "{" + subjectPart + """"purpose":"$purpose","recipient":{"email":"a@client.example",""" +
            """"mobileNumber":"+6591234567"},"expiry":"${now.plusSeconds(3600)}"}"""
    }

    private fun quoted(text: String) = "\"$text\""

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
        entity = EntityRef("ENV-1"),
        docUuid = null,
        transactionDate = null,
        docSequence = null,
        instanceId = "masterdata-svc-0",
        correlation = CorrelationId("c-$id"),
        causation = CausationId("c-$id"),
        dataJson = data,
    )

    private fun rows(type: String) = captured.appended.filter { it.event.type == type }

    private fun schema(name: String) =
        checkNotNull(javaClass.classLoader.getResourceAsStream("gst/contracts/access/schemas/$name")).use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    private fun properties(json: String) = mapper.readTree(json).propertyNames().toSet()

    @Test
    fun `INV-AC-18218 one handling gives one SignedLinkIssued equal to the stored link and a redelivery gives none`() {
        val event = envelope(payload(subject = quoted("R1")))
        handler.handle(event)
        handler.handle(event)
        val link = stored.values.single()
        val issued = rows(SignedLinkEventOutbox.ISSUED_TYPE).single()
        val body = mapper.readTree(issued.event.dataJson)
        assertEquals(link.ref.value, body.get("linkRef").asString())
        assertEquals("R1", body.get("subjectReference").asString())
        assertEquals("CONFIRM_FILING", body.get("purpose").asString())
        assertEquals(tenant.value.toString(), body.get("tenant").asString())
        assertEquals(link.expiresAt, Instant.parse(body.get("expiry").asString()))
        assertEquals(1, stored.size)
        assertEquals(2, rows("com.dynokore.gst.access.MessageRequested.v1").size)
    }

    @Test
    fun `INV-AC-18218 the payload of SignedLinkIssued has the property names of the contract schema and validates`() {
        handler.handle(envelope(payload(subject = quoted("R1"))))
        val json = rows(SignedLinkEventOutbox.ISSUED_TYPE).single().event.dataJson
        assertEquals(setOf("linkRef", "subjectReference", "purpose", "tenant", "expiry"), properties(json))
        val contract = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        val violations =
            contract.getSchema(schema("signed-link-issued-payload.schema.json")).validate(json, InputFormat.JSON)
        assertTrue(violations.isEmpty(), violations.toString())
        assertTrue(sentPasscodes.none { json.contains(it) } && !json.contains("a@client.example"))
        assertTrue(!json.contains("+6591234567") && !json.contains("sealed"))
    }

    @Test
    fun `INV-AC-18218 a failure after the link store rolls the link and the row back, counts 0 and 0`() {
        failAfterStore = true
        assertThrows(IllegalStateException::class.java) {
            handler.handle(envelope(payload(subject = quoted("R1"))))
        }
        assertEquals(0, stored.size)
        assertEquals(0, rows(SignedLinkEventOutbox.ISSUED_TYPE).size)
        assertEquals(0, captured.appended.size)
    }

    @Test
    fun `INV-AC-18218 a look-alike purpose string reaches the payload unchanged and changes no other property`() {
        handler.handle(envelope(payload(subject = quoted("R1"), purpose = "CONFIRM_FILING "), id = "e1"))
        handler.handle(envelope(payload(subject = quoted("R2"), purpose = "confirm_filing"), id = "e2"))
        val purposes =
            rows(SignedLinkEventOutbox.ISSUED_TYPE).map { mapper.readTree(it.event.dataJson).get("purpose").asString() }
        assertEquals(listOf("CONFIRM_FILING ", "confirm_filing"), purposes)
    }

    @Test
    fun `INV-AC-18218 four wrong entries write no invalidation, the fifth one, a sixth and a correct entry none`() {
        handler.handle(envelope(payload(subject = quoted("R1"))))
        val ref = stored.values.single().ref
        val correct = sentPasscodes.single()
        val wrong = if (correct == "000000") "111111" else "000000"
        repeat(4) { assertTrue(verify.handle(ref, wrong) is VerifyLinkOutcome.Wrong) }
        assertEquals(0, rows(SignedLinkEventOutbox.INVALIDATED_TYPE).size)
        assertTrue(verify.handle(ref, wrong) is VerifyLinkOutcome.Invalidated)
        assertEquals(1, rows(SignedLinkEventOutbox.INVALIDATED_TYPE).size)
        assertEquals(VerifyLinkOutcome.Gone, verify.handle(ref, wrong))
        assertEquals(VerifyLinkOutcome.Gone, verify.handle(ref, correct))
        val invalidated = rows(SignedLinkEventOutbox.INVALIDATED_TYPE)
        assertEquals(1, invalidated.size)
        val body = mapper.readTree(invalidated.single().event.dataJson)
        assertEquals(ref.value, body.get("linkRef").asString())
        assertEquals("R1", body.get("subjectReference").asString())
        val reasons = invalidated.map { mapper.readTree(it.event.dataJson).get("reason").asString() }.toSet()
        assertEquals(setOf("ATTEMPTS_EXHAUSTED"), reasons)
        assertEquals(
            setOf("linkRef", "subjectReference", "reason", "invalidatedAt"),
            properties(invalidated.single().event.dataJson),
        )
        val contract = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        val violations =
            contract
                .getSchema(schema("signed-link-invalidated-payload.schema.json"))
                .validate(invalidated.single().event.dataJson, InputFormat.JSON)
        assertTrue(violations.isEmpty(), violations.toString())
    }

    private fun refused(data: String) {
        assertThrows(ContractViolation::class.java) { handler.handle(envelope(data)) }
        assertEquals(0, stored.size)
        assertEquals(0, captured.appended.size)
    }

    @Test
    fun `INV-AC-18218 a subjectReference of exactly 40 characters is accepted and carried unchanged`() {
        val forty = "S".repeat(40)
        handler.handle(envelope(payload(subject = quoted(forty))))
        assertEquals(1, stored.size)
        val row = rows(SignedLinkEventOutbox.ISSUED_TYPE).single()
        assertEquals(forty, mapper.readTree(row.event.dataJson).get("subjectReference").asString())
    }

    @Test
    fun `INV-AC-18218 a subjectReference of 41 characters stores nothing`() =
        refused(payload(subject = quoted("S".repeat(41))))

    @Test
    fun `INV-AC-18218 a missing subjectReference stores nothing`() = refused(payload(subject = null))

    @Test
    fun `INV-AC-18218 an empty subjectReference stores nothing`() = refused(payload(subject = quoted("")))

    @Test
    fun `INV-AC-18218 a blank subjectReference stores nothing`() = refused(payload(subject = quoted("   ")))

    @Test
    fun `INV-AC-18218 a null subjectReference stores nothing`() = refused(payload(subject = "null"))

    @Test
    fun `INV-AC-18218 a number instead of a string subjectReference stores nothing`() = refused(payload(subject = "7"))

    @Test
    fun `INV-AC-18218 a lower-case look-alike property subjectreference counts as missing`() =
        refused(payload(subject = quoted("R1"), subjectKey = "subjectreference"))
}
