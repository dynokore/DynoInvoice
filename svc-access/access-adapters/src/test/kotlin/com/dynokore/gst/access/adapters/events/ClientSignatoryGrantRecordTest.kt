package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.link.ClientSignatoryGrants
import com.dynokore.gst.access.application.link.IssueLinkCommand
import com.dynokore.gst.access.application.link.IssueLinkOutcome
import com.dynokore.gst.access.application.link.IssueSignedLinkUseCase
import com.dynokore.gst.access.application.link.LinkPasscodeSetting
import com.dynokore.gst.access.application.link.PasscodeDigests
import com.dynokore.gst.access.application.link.RemovingClientSignatoryOnInvalidation
import com.dynokore.gst.access.application.link.SecureLinkSecrets
import com.dynokore.gst.access.application.link.SignedLinks
import com.dynokore.gst.access.application.link.VerifySignatoryLinkUseCase
import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.link.LinkRecipient
import com.dynokore.gst.access.domain.link.LinkRef
import com.dynokore.gst.access.domain.link.LinkSource
import com.dynokore.gst.access.domain.link.LinkStatus
import com.dynokore.gst.access.domain.link.SignedLink
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.ScopeLevel
import com.dynokore.platform.productruntime.PlatformEventCatalogue
import com.dynokore.platform.productruntime.feed.AuthorityFeedHandler
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.CellAllowance
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.DecisionSource
import com.dynokore.platform.serviceruntime.replica.OperationRoleMap
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import com.dynokore.platform.outboxandqueue.EntityRef as WireEntityRef

/**
 * INV-Q-9222 A: the role record `client.signatory` at CLIENT_ENTITY scope that a client-signatory link stands for, and
 * its removal. The use cases run on in-memory ports over the real outbox mapper and a capturing writer; the transaction
 * fake rolls the store and the outbox back together. The round trip feeds the decoded events to a fixture replica
 * whose operation map is the only place that names the role string.
 */
class ClientSignatoryGrantRecordTest {
    private val tenant = TenantId(UUID.fromString("0a000000-0000-4000-8000-00000000000a"))
    private val mapper = JsonMapper.builder().build()
    private val now = Instant.parse("2026-11-09T02:00:00Z")
    private val expiry = now.plusSeconds(3600)
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val captured = CapturedEvents()
    private val stored = linkedMapOf<String, SignedLink>()
    private val versions = AtomicLong(0)
    private var failAfterStore = false
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
            ) = "d:${ref.value}:$passcode"

            override fun matches(
                ref: LinkRef,
                passcode: String,
                digest: String,
            ) = digestOf(ref, passcode) == digest
        }
    private val sent = mutableListOf<String>()
    private val noAudit =
        object : GrantAudit {
            override fun record(entry: GrantAuditEntry) = Unit
        }
    private val grantsOut = ClientSignatoryGrantOutbox(
            captured,
            tenants,
            mapper,
            InMemoryAuthorityHead(),
            InMemoryAuthorityIncarnation(),
        ) { versions.incrementAndGet() }
    private val messages = OutboxLinkMessages(captured, tenants, mapper)
    private val issue =
        IssueSignedLinkUseCase(
            tx,
            links,
            SecureLinkSecrets(),
            digests,
            { passcode, _ ->
                sent += passcode
                "sealed"
            },
            { LinkPasscodeSetting.DEFAULT },
            { false },
            messages,
            grantsOut,
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
            RemovingClientSignatoryOnInvalidation(SignedLinkEventOutbox(captured, tenants, mapper), grantsOut),
            clock,
        )

    private fun command(
        consent: ConsentRef? = ConsentRef("C-1"),
        client: EntityRef? = EntityRef("CLI-2201"),
        envelope: EntityRef? = null,
        eventId: String? = null,
    ) = IssueLinkCommand(
        "SIGN_CONSENT",
        "SUBJ-1",
        "s@client.example",
        "+6591234567",
        expiry,
        consent,
        client,
        envelope,
        eventId,
    )

    private fun issued(command: IssueLinkCommand = command()): SignedLink {
        val under =
            com.dynokore.platform.outboxandqueue
                .EntityRef((command.client ?: command.envelopeEntity)!!.value)
        val out = messages.filedUnder(under) { issue.handle(command) }
        return stored.getValue((out as IssueLinkOutcome.Issued).ref.value)
    }

    private fun grantEvents() =
        captured.appended.filter { it.event.type == PlatformEventCatalogue.AUTHORITY_CHANGED.eventType }

    private fun invalidate(link: SignedLink) {
        val passcode = sent.last()
        val wrong = if (passcode == "000000") "000001" else "000000"
        repeat(5) { verify.handle(link.ref, wrong) }
    }

    private val schema =
        checkNotNull(javaClass.classLoader.getResourceAsStream(SCHEMA)) { "$SCHEMA is not on the classpath" }
            .use {
                SchemaRegistry
                    .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(it.readBytes().toString(Charsets.UTF_8))
            }

    @Test
    fun `INV-AC-926 the ACTIVE record equals the expected fields and validates against the schema`() {
        val link = issued()
        val event = grantEvents().single()
        val json = mapper.readTree(event.event.dataJson)
        assertTrue(schema.validate(event.event.dataJson, com.networknt.schema.InputFormat.JSON).isEmpty())
        assertEquals("ROLE_GRANT", json["changeKind"].asString())
        assertEquals("link:${link.ref.value}", json["principalRef"].asString())
        assertEquals(1L, json["authorityVersion"].asLong())
        val record = json["roleGrantRecord"]
        assertEquals(link.ref.value, record["grantRef"].asString())
        assertEquals(Role.CLIENT_SIGNATORY.wire, record["role"].asString())
        assertEquals("ACTIVE", record["state"].asString())
        assertEquals("CLIENT_ENTITY", record["scopeLevel"].asString())
        assertEquals("CLI-2201", record["scopeRef"].asString())
        assertEquals(now, Instant.parse(record["validFrom"].asString()))
        assertEquals(expiry, Instant.parse(record["validUntil"].asString()))
        assertFalse(record.has("periodFrom"))
        assertFalse(record.has("periodTo"))
    }

    @Test
    fun `INV-AC-926 the envelope is the platform type, tenant-wide and keyed on the principal`() {
        val link = issued()
        val context = grantEvents().single().context
        assertEquals(PlatformEventCatalogue.AUTHORITY_CHANGED.eventType, grantEvents().single().event.type)
        assertEquals(tenant, context.tenant)
        assertEquals(WireEntityRef.TENANT_WIDE, context.entity)
        assertEquals("link:${link.ref.value}", context.aggregateKey)
    }

    @Test
    fun `INV-AC-926 a counterparty link issued on the event publishes no role record`() {
        issued(command(consent = null, client = null, envelope = EntityRef("E-ENV"), eventId = "ev-1"))
        assertTrue(grantEvents().isEmpty())
    }

    @Test
    fun `INV-AC-2765 the fifth wrong passcode publishes one REMOVED record, same grantRef, higher version`() {
        val link = issued()
        invalidate(link)
        val (active, removed) = grantEvents().map { mapper.readTree(it.event.dataJson) }
        assertEquals(2, grantEvents().size)
        assertEquals(active["roleGrantRecord"]["grantRef"], removed["roleGrantRecord"]["grantRef"])
        assertEquals("REMOVED", removed["roleGrantRecord"]["state"].asString())
        assertTrue(removed["authorityVersion"].asLong() > active["authorityVersion"].asLong())
        assertTrue(schema.validate(grantEvents()[1].event.dataJson, com.networknt.schema.InputFormat.JSON).isEmpty())
    }

    @Test
    fun `INV-AC-926 a failure after the link store leaves no link and no record`() {
        failAfterStore = true
        assertThrows(IllegalStateException::class.java) { issued() }
        assertTrue(stored.isEmpty())
        assertTrue(grantEvents().isEmpty())
    }

    @Test
    fun `INV-AC-926 a failure of the outbox append rolls the issue back`() {
        val failing =
            ClientSignatoryGrantOutbox(
            captured,
            tenants,
            mapper,
            InMemoryAuthorityHead(),
            InMemoryAuthorityIncarnation(),
        ) { error("injected failure of the version draw") }
        val use =
            IssueSignedLinkUseCase(
                tx,
                links,
                SecureLinkSecrets(),
                digests,
                { _, _ -> "sealed" },
                { LinkPasscodeSetting.DEFAULT },
                { false },
                OutboxLinkMessages(captured, tenants, mapper),
                failing,
                noAudit,
                "system:access-svc",
                clock,
            )
        assertThrows(IllegalStateException::class.java) {
            messages.filedUnder(WireEntityRef("CLI-2201")) { use.handle(command()) }
        }
        assertTrue(stored.isEmpty())
        assertTrue(captured.appended.isEmpty())
    }

    // round trip ---------------------------------------------------------------------------------------------------

    private val operations =
        listOf("vp-04.set", "vp-10.set", "vp-11.set", "vp-12.set", "vp-13.set").associateWith {
            mapOf(Role.CLIENT_SIGNATORY.wire to CellAllowance.WITHIN_SCOPE)
        }

    private fun replicaAt(at: Instant): Pair<AuthorityReplica, Clock> {
        val c = Clock.fixed(at, ZoneOffset.UTC)
        return AuthorityReplica(c, OperationRoleMap { operations[it].orEmpty() }, fallback = null, structure = null)
            .also { it.onSnapshotComplete(0) } to c
    }

    private fun feed(
        replica: AuthorityReplica,
        events: List<CapturedEvents.Appended>,
    ) = events.forEach { AuthorityFeedHandler(replica).handle(it.delivered()) }

    private fun decide(
        replica: Pair<AuthorityReplica, Clock>,
        principal: String = "link:LNK-771A",
        entity: String = "CLI-2201",
        action: String = "vp-10.set",
        level: ScopeLevel = ScopeLevel.CLIENT_ENTITY,
        on: TenantId = tenant,
    ): AuthorityDecision =
        replica.first.decide(
            AuthorizationRequest(on, Principal(principal, PrincipalKind.HUMAN), entity, action, level = level),
            Deadline.after(Duration.ofMillis(500), replica.second),
        )

    private fun linkNamed(ref: String): SignedLink =
        SignedLink(
            LinkRef(ref),
            "SIGN_CONSENT",
            EntityRef("CLI-2201"),
            ConsentRef("C-1"),
            EntityRef("CLI-2201"),
            "SUBJ-1",
            LinkRecipient("s@client.example", null),
            expiry,
            "d",
            LinkStatus.ACTIVE,
            0,
            null,
            null,
            LinkSource.CALL,
            null,
            0,
        )

    private val granted = AuthorityDecision(true, DecisionSource.LOCAL, DecisionReason.GRANTED)
    private val denied = AuthorityDecision(false, DecisionSource.LOCAL, DecisionReason.NO_GRANT)

    @Test
    fun `INV-AC-926 the replica allows the link principal on its client entity and refuses every look-alike`() {
        grantsOut.issued(linkNamed("LNK-771A"), now)
        val replica = replicaAt(now).also { feed(it.first, captured.appended.toList()) }
        assertEquals(granted, decide(replica))
        listOf("vp-04.set", "vp-11.set", "vp-12.set", "vp-13.set").forEach {
            assertEquals(granted, decide(replica, action = it))
        }
        assertEquals(denied, decide(replica, action = "vp-30.set"))
        assertEquals(denied, decide(replica, principal = "link:LNK-771B"))
        assertEquals(denied, decide(replica, principal = "LNK-771A"))
        assertEquals(denied, decide(replica, entity = "CLI-2202"))
        assertEquals(denied, decide(replica, on = TenantId(UUID.randomUUID())))
        assertEquals(denied, decide(replica, action = "vp-10.set "))
        assertEquals(denied, decide(replica, action = Role.CLIENT_SIGNATORY.wire))
        assertEquals(denied, decide(replica, level = ScopeLevel.TENANT))
        assertEquals(denied, decide(replica, level = ScopeLevel.ENTITY))
    }

    @Test
    fun `INV-AC-926 the replica refuses after the expiry`() {
        grantsOut.issued(linkNamed("LNK-771A"), now)
        val replica = replicaAt(expiry).also { feed(it.first, captured.appended.toList()) }
        assertEquals(denied, decide(replica))
    }

    @Test
    fun `INV-AC-2765 the replica refuses the same request after the REMOVED record`() {
        val link = linkNamed("LNK-771A")
        grantsOut.issued(link, now)
        grantsOut.removed(link, now.plusSeconds(60))
        val replica = replicaAt(now.plusSeconds(120)).also { feed(it.first, captured.appended.toList()) }
        assertEquals(denied, decide(replica))
    }

    private companion object {
        const val SCHEMA = "gst/contracts/service-runtime/schemas/authority-changed-payload.schema.json"
    }
}
