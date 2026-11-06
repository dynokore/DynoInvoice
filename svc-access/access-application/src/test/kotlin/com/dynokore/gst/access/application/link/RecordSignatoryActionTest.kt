package com.dynokore.gst.access.application.link

import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.consent.ConsentStatus
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.link.LinkRef
import com.dynokore.gst.access.domain.link.LinkStatus
import com.dynokore.gst.access.domain.link.SignedLink
import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionDefinition
import com.dynokore.gst.access.domain.role.PermissionKind
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

private typealias Kind = SignatoryActionKind

/** `recordSignatoryAction` at the use case boundary with fake ports (INV-REQ-900, INV-REQ-1010). */
class RecordSignatoryActionTest {
    private val kit = LinkKit()

    init {
        kit.consents["C-A"] = kit.consent("C-A", kit.clientA)
        kit.consents["C-A2"] =
            kit
                .consent("C-A2", kit.clientA)
                .copy(status = ConsentStatus.WITHDRAWN, handoverStatementRef = "H")
        kit.consents["C-B"] = kit.consent("C-B", kit.clientB)
    }

    private fun verifiedLink(): SignedLink {
        val (link, passcode) = kit.issued()
        kit.verify.handle(link.ref, passcode)
        return kit.stored.getValue(link.ref.value)
    }

    @Suppress("LongParameterList") // one default per field of the command, so a test names only what it varies
    private fun cmd(
        link: SignedLink,
        kind: SignatoryActionKind,
        pack: String? = if (kind == Kind.WITHDRAW) null else "2026.3",
        query: String? = if (kind == Kind.QUERY) "why?" else null,
        consentRef: String? = null,
        clientRef: String? = null,
    ) = SignatoryActionCommand(kit.tenant, link.ref, kind, pack, query, consentRef, clientRef)

    @Test
    fun `an unknown expired or not yet verified link is unauthenticated and stores nothing`() {
        val (fresh, _) = kit.issued()
        assertEquals(SignatoryActionOutcome.Unauthenticated, kit.record.handle(cmd(fresh, Kind.ACKNOWLEDGE)))
        val unknown = cmd(fresh, Kind.ACKNOWLEDGE).copy(linkRef = LinkRef("LNK-nope"))
        assertEquals(SignatoryActionOutcome.Unauthenticated, kit.record.handle(unknown))
        val verified = verifiedLink()
        kit.now = kit.now.plus(Duration.ofHours(2))
        assertEquals(SignatoryActionOutcome.Unauthenticated, kit.record.handle(cmd(verified, Kind.WITHDRAW)))
        assertTrue(kit.evidence.isEmpty() && kit.withdrawals.isEmpty() && kit.acks.isEmpty())
    }

    @Test
    fun `an invalidated or revoked link gives INVALIDATED`() {
        val link = verifiedLink()
        for (status in listOf(LinkStatus.INVALIDATED, LinkStatus.REVOKED)) {
            kit.stored[link.ref.value] = link.copy(status = status)
            assertEquals(SignatoryActionOutcome.Invalidated, kit.record.handle(cmd(link, Kind.QUERY)))
        }
        assertTrue(kit.evidence.isEmpty())
    }

    @Test
    fun `input that carries consentRef or clientRef is invalid`() {
        val link = verifiedLink()
        val carrying =
            listOf(cmd(link, Kind.WITHDRAW, consentRef = "C-B"), cmd(link, Kind.ACKNOWLEDGE, clientRef = "E-B"))
        for (c in carrying) {
            assertTrue(kit.record.handle(c) is SignatoryActionOutcome.Invalid)
        }
        assertTrue(kit.record.handle(cmd(link, Kind.ACKNOWLEDGE, pack = null)) is SignatoryActionOutcome.Invalid)
        assertTrue(kit.record.handle(cmd(link, Kind.QUERY, query = null)) is SignatoryActionOutcome.Invalid)
        assertTrue(kit.evidence.isEmpty() && kit.withdrawals.isEmpty())
    }

    @Test
    fun `WITHDRAW withdraws exactly the consent of the link and a repeat is a no-op`() {
        val link = verifiedLink()
        val first = kit.record.handle(cmd(link, Kind.WITHDRAW)) as SignatoryActionOutcome.Recorded
        assertEquals(false, first.repeated)
        assertEquals(ConsentStatus.WITHDRAWN, kit.consents.getValue("C-A").status)
        assertEquals(ConsentStatus.LIVE, kit.consents.getValue("C-B").status)
        assertEquals(listOf("C-A"), kit.withdrawals)
        val again = kit.record.handle(cmd(link, Kind.WITHDRAW)) as SignatoryActionOutcome.Recorded
        assertTrue(again.repeated)
        assertEquals(listOf("C-A"), kit.withdrawals)
        assertEquals(1, kit.evidence.size)
        assertEquals("link:${link.ref.value}", kit.evidence.single().actor)
        assertTrue(kit.audits.none { it.actor.contains("client.signatory") })
    }

    @Test
    fun `a link of client A never reads or changes a consent of client B`() {
        val (link, passcode) = kit.issued(kit.command(consent = ConsentRef("C-B")))
        kit.verify.handle(link.ref, passcode)
        // the link names C-B but its client entity is A: the consent of A is live and is not C-B
        val out = kit.record.handle(cmd(link, Kind.WITHDRAW))
        assertEquals(SignatoryActionOutcome.ConsentNotLive, out)
        assertEquals(ConsentStatus.LIVE, kit.consents.getValue("C-B").status)
        // the only read is through the link's own client entity A; nothing of client B or of C-B is touched
        assertEquals(listOf("client:E-A"), kit.reads.toList())
        assertTrue(kit.withdrawals.isEmpty())
    }

    @Test
    fun `ACKNOWLEDGE and QUERY each store exactly one evidence record with the link actor`() {
        val link = verifiedLink()
        assertTrue(kit.record.handle(cmd(link, Kind.ACKNOWLEDGE)) is SignatoryActionOutcome.Recorded)
        assertTrue(kit.record.handle(cmd(link, Kind.QUERY)) is SignatoryActionOutcome.Recorded)
        assertEquals(2, kit.evidence.size)
        assertTrue(kit.evidence.all { it.actor == "link:${link.ref.value}" && it.client == kit.clientA })
        assertEquals(listOf(listOf("2026.3", "E-A", "link:${link.ref.value}")), kit.acks)
        val repeat = kit.record.handle(cmd(link, Kind.ACKNOWLEDGE)) as SignatoryActionOutcome.Recorded
        assertTrue(repeat.repeated)
        assertEquals(2, kit.evidence.size)
    }

    @Test
    fun `a key outside the client signatory cells of the catalogue is forbidden`() {
        // grant.role is a tenant.admin cell in role-permissions.yaml; client.signatory holds no cell for it
        val link = verifiedLink()
        val permissions = LinkPermissions(kit.matrix, kit.directory)
        assertEquals(false, permissions.allows(link, Permission("grant.role"), kit.now))
        assertEquals(true, permissions.allows(link, LinkPermissions.CONSENT_WITHDRAW, kit.now))
        // a catalogue without the cell for the key forbids the action
        val bare =
            PermissionMatrix(
                listOf(PermissionDefinition(LinkPermissions.CONSENT_WITHDRAW, PermissionKind.WRITE)),
                mapOf(Role.CLIENT_SIGNATORY to emptyMap<Permission, Allowance>()),
            )
        val forbidden =
            RecordSignatoryActionUseCase(
                kit.tx,
                kit.links,
                LinkPermissions(bare, kit.directory),
                kit.actions,
                kit.consentRepo,
                kit.withdrawal,
                kit.acknowledgements,
                kit.audit,
                kit.clock,
            )
        assertEquals(SignatoryActionOutcome.Forbidden, forbidden.handle(cmd(link, Kind.WITHDRAW)))
        assertTrue(kit.evidence.isEmpty() && kit.withdrawals.isEmpty())
    }

    private fun eventLink(entity: EntityRef): SignedLink {
        val event = kit.command(consent = null, client = null, envelopeEntity = entity, eventId = "e-${entity.value}")
        val (link, passcode) = kit.issued(event)
        kit.verify.handle(link.ref, passcode)
        return kit.stored.getValue(link.ref.value)
    }

    @Test
    fun `ACKNOWLEDGE and QUERY on a valid event-issued link are recorded against the envelope entity`() {
        val link = eventLink(kit.envelope)
        assertEquals(kit.envelope, link.boundEntity)
        assertTrue(kit.record.handle(cmd(link, Kind.ACKNOWLEDGE)) is SignatoryActionOutcome.Recorded)
        assertTrue(kit.record.handle(cmd(link, Kind.QUERY)) is SignatoryActionOutcome.Recorded)
        assertEquals(2, kit.evidence.size)
        assertTrue(kit.evidence.all { it.client == kit.envelope && it.actor == "link:${link.ref.value}" })
        assertEquals(listOf(listOf("2026.3", "E-ENV", "link:${link.ref.value}")), kit.acks)
        assertTrue(kit.audits.all { it.scope == "entity:E-ENV" })
    }

    @Test
    fun `an event-issued link whose alert entity has no membership is forbidden and stores nothing`() {
        val link = eventLink(kit.orphan)
        for (kind in Kind.values()) {
            assertEquals(SignatoryActionOutcome.Forbidden, kit.record.handle(cmd(link, kind)))
        }
        assertTrue(kit.evidence.isEmpty() && kit.acks.isEmpty() && kit.withdrawals.isEmpty())
    }

    @Test
    fun `WITHDRAW gives the same result when the link names no consent, another client's, or a dead one`() {
        kit.consents["C-A"] =
            kit.consents.getValue("C-A").copy(status = ConsentStatus.WITHDRAWN, handoverStatementRef = "H")
        val noConsent = eventLink(kit.envelope)
        val foreign = verifiedWith(kit.command(consent = ConsentRef("C-B")))
        val dead = verifiedWith(kit.command(consent = ConsentRef("C-A")))
        val results = listOf(noConsent, foreign, dead).map { kit.record.handle(cmd(it, Kind.WITHDRAW)) }
        assertEquals(listOf(SignatoryActionOutcome.ConsentNotLive), results.distinct())
        assertTrue(kit.evidence.isEmpty() && kit.withdrawals.isEmpty())
        assertEquals(ConsentStatus.LIVE, kit.consents.getValue("C-B").status)
    }

    private fun verifiedWith(command: IssueLinkCommand): SignedLink {
        val (link, passcode) = kit.issued(command)
        kit.verify.handle(link.ref, passcode)
        return kit.stored.getValue(link.ref.value)
    }
}
