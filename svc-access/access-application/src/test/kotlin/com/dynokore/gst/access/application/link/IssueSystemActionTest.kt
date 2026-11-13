package com.dynokore.gst.access.application.link

import com.dynokore.gst.access.application.consent.ConsentRepository
import com.dynokore.gst.access.domain.consent.Consent
import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.role.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * INV-AC-17419 (INV-Q-17201): issuing a signatory link is a system action. No permission key, no agent scope, no role
 * decision; the audit entry names the system actor and the triggering event.
 */
class IssueSystemActionTest {
    private val kit = LinkKit()
    private val roleNames = Role.entries.map { it.wire }

    private fun event(id: String = "ev-1") =
        kit.command(consent = null, client = null, envelopeEntity = kit.clientA, eventId = id)

    @Test
    fun `INV-AC-17419 the system actor issues the link and the audit entry names the system actor and the event`() {
        val out = kit.issue.handle(kit.systemActor, event("ev-9"))
        val link = kit.stored.getValue((out as IssueLinkOutcome.Issued).ref.value)
        val entry = kit.audits.single()
        assertEquals(kit.systemActor, entry.actor)
        assertFalse(entry.actor in roleNames)
        assertEquals("ev-9", entry.reason)
        assertEquals("entity:${kit.clientA.value}", entry.scope)
        assertEquals("""{"link":null}""", entry.priorStateJson)
        assertEquals(kit.clientA, link.alertEntity)
        assertEquals(2, kit.messages.size)
    }

    /** Every method of the consent read port records its call; the issue path must make none. */
    private class RecordingConsents : ConsentRepository {
        val calls = mutableListOf<String>()

        override fun find(ref: ConsentRef): Consent? = null.also { calls += "find" }

        override fun lock(ref: ConsentRef): Consent? = null.also { calls += "lock" }

        override fun findLiveForClient(client: EntityRef): Consent? = null.also { calls += "findLiveForClient" }

        override fun insert(consent: Consent) {
            calls += "insert"
        }

        override fun update(
            before: Consent,
            after: Consent,
        ) {
            calls += "update"
        }

        override fun nextAuthorityVersion(): Long = 1L.also { calls += "nextAuthorityVersion" }

        override fun nextRef(): ConsentRef = ConsentRef("C-X").also { calls += "nextRef" }
    }

    @Test
    fun `INV-AC-17419 no agent-scope lookup and no consent read on the issue path`() {
        val consents = RecordingConsents()
        val watched = LinkKit(consentRepository = consents)
        val out = watched.issue.handle(watched.systemActor, event())
        assertTrue(out is IssueLinkOutcome.Issued)
        assertTrue(consents.calls.isEmpty(), consents.calls.toString())
    }

    @Test
    fun `INV-AC-17419 the use case constructor takes no AuthorityReplica and no agent scope collaborator`() {
        val types =
            IssueSignedLinkUseCase::class.java.constructors
                .flatMap { c -> c.parameterTypes.toList() }
                .map { it.name }
        assertTrue(types.isNotEmpty())
        assertTrue(types.none { it.contains("AuthorityReplica") || it.contains("AgentScope") }, types.toString())
    }

    @Test
    fun `INV-AC-17419 probe a role named actor gets no link and no audit row`() {
        assertTrue("agent.operator" in roleNames)
        roleNames.forEach { role ->
            assertEquals(
                IssueLinkOutcome.Refused(IssueRefusal.ACTOR_NOT_SYSTEM),
                kit.issue.handle(role, event("ev-$role")),
            )
        }
        assertTrue(kit.stored.isEmpty() && kit.messages.isEmpty() && kit.audits.isEmpty() && kit.issuedLinks.isEmpty())
    }

    @Test
    fun `INV-AC-17419 probe a blank or look-alike actor gets no link and no audit row`() {
        listOf("", " ", "system:access-svc ", "SYSTEM:ACCESS-SVC", "system:access-svc2").forEach {
            assertEquals(IssueLinkOutcome.Refused(IssueRefusal.ACTOR_NOT_SYSTEM), kit.issue.handle(it, event()))
        }
        assertTrue(kit.stored.isEmpty() && kit.messages.isEmpty() && kit.audits.isEmpty())
    }
}
