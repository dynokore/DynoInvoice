package com.dynokore.gst.access.application

import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.invitation.FirstAdministratorEffects
import com.dynokore.gst.access.application.invitation.FirstAdministratorInvitations
import com.dynokore.gst.access.application.invitation.InvitationCodes
import com.dynokore.gst.access.application.invitation.InvitationIdentity
import com.dynokore.gst.access.application.invitation.InvitationKeyUnavailable
import com.dynokore.gst.access.application.invitation.InvitationRecord
import com.dynokore.gst.access.application.invitation.InvitationRefs
import com.dynokore.gst.access.application.invitation.InvitationSettings
import com.dynokore.gst.access.application.invitation.LiveAdminGrant
import com.dynokore.gst.access.application.invitation.NewInvitation
import com.dynokore.gst.access.application.invitation.ProvisionFirstAdministrator
import com.dynokore.gst.access.application.invitation.ProvisionFirstAdministratorCommand
import com.dynokore.gst.access.application.invitation.ProvisionFirstAdministratorOutcome
import com.dynokore.gst.access.application.invitation.StoredInvitation
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** INV-T-8226 at the use-case level: the invitation reference bound and the fail-closed paths store nothing. */
class ProvisionFirstAdministratorTest {
    private val tenant = TenantRef("0a000000-0000-4000-8000-00000000000a")

    private class Rows : GrantRepository {
        val grants = mutableListOf<Grant>()
        val identities = mutableListOf<IdentityRef>()
        var counter = 0L

        override fun findByIdentity(identity: IdentityRef) = grants.filter { it.identity == identity }

        override fun find(ref: GrantRef) = grants.firstOrNull { it.ref == ref }

        override fun findByRepeatKey(
            identity: IdentityRef,
            roleWire: String,
            scope: Scope,
        ): Grant? = null

        override fun insert(grant: Grant) {
            grants += grant
        }

        override fun update(
            before: Grant,
            after: Grant,
        ) = error("not used")

        override fun lockIdentity(
            identity: IdentityRef,
            kind: GranteeKind,
        ) {
            identities += identity
        }

        override fun nextAuthorityVersion() = ++counter

        override fun nextGrantRef() = GrantRef("G-${++counter}")
    }

    private class Invitations : FirstAdministratorInvitations {
        val stored = mutableListOf<NewInvitation>()

        override fun lockTenant() = Unit

        override fun findByKey(key: UUID): StoredInvitation? = null

        override fun insert(invitation: NewInvitation) {
            stored += invitation
        }

        override fun findByRef(invitationRef: String): InvitationRecord? = null

        override fun markAccepted(
            invitationRef: String,
            acceptKey: java.util.UUID,
            acceptFingerprint: String,
        ) = error("not used")

        override fun holdsLiveTenantAdminGrant() = false

        override fun liveTenantAdminGrants() = emptyList<LiveAdminGrant>()

        override fun reissue(
            grantRef: String,
            invitationRef: String,
            codeDigest: String,
            codeKeyVersion: String,
            expiresAt: java.time.Instant,
        ) = error("not used")
    }

    private class Audit : GrantAudit {
        val entries = mutableListOf<GrantAuditEntry>()

        override fun record(entry: GrantAuditEntry) {
            entries += entry
        }
    }

    private val tx =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T = block()
        }

    private val codes =
        object : InvitationCodes {
            override fun codeOf(
                tenant: TenantRef,
                invitationRef: String,
                keyVersion: String,
            ) = "code-$invitationRef"

            override fun digestOf(code: String) = "digest-$code"
        }

    private fun useCase(
        rows: Rows,
        invitations: Invitations,
        refs: InvitationRefs,
        codes: InvitationCodes = this.codes,
    ) = ProvisionFirstAdministrator(
        tx,
        rows,
        FirstAdministratorEffects(invitations, codes, refs, Audit()),
        Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC),
        InvitationSettings("v1"),
    )

    private val command =
        ProvisionFirstAdministratorCommand(
            tenant,
            "operator-1",
            UUID.randomUUID(),
            "a@example.sg",
            Instant.parse("2030-01-01T00:00:00Z"),
        )

    @Test
    fun `an invitation reference of 49 characters fails closed and stores nothing`() {
        val rows = Rows()
        val invitations = Invitations()
        val tooLong = InvitationRefs { "INVN-" + "x".repeat(44) }
        assertEquals(49, tooLong.next().length)
        assertThrows(IllegalStateException::class.java) { useCase(rows, invitations, tooLong).handle(command) }
        assertTrue(rows.grants.isEmpty() && rows.identities.isEmpty() && invitations.stored.isEmpty())
    }

    @Test
    fun `a reference of 48 characters is stored with the identity of at most 59 characters`() {
        val rows = Rows()
        val invitations = Invitations()
        val ref = "INVN-" + "x".repeat(43)
        val outcome = useCase(rows, invitations, { ref }).handle(command)
        assertTrue(outcome is ProvisionFirstAdministratorOutcome.Provisioned)
        assertEquals(InvitationIdentity.PREFIX + ref, rows.identities.single().value)
        assertTrue(
            rows.identities
                .single()
                .value.length <= 59,
        )
        assertEquals("digest-code-$ref", invitations.stored.single().codeDigest)
    }

    @Test
    fun `an unavailable code key stores nothing`() {
        val rows = Rows()
        val invitations = Invitations()
        val failing =
            object : InvitationCodes by codes {
                override fun codeOf(
                    tenant: TenantRef,
                    invitationRef: String,
                    keyVersion: String,
                ): String = throw InvitationKeyUnavailable()
            }
        val outcome = useCase(rows, invitations, { "INVN-a" }, failing).handle(command)
        assertEquals(ProvisionFirstAdministratorOutcome.CodeKeyUnavailable, outcome)
        assertTrue(rows.grants.isEmpty() && rows.identities.isEmpty() && invitations.stored.isEmpty())
    }

    @Test
    fun `the reserved prefix is matched exactly and case-sensitively`() {
        assertTrue(InvitationIdentity.isReserved("invitation:INVN-X"))
        assertFalse(InvitationIdentity.isReserved("invitations:INVN-X"))
        assertFalse(InvitationIdentity.isReserved("Invitation:INVN-X"))
        assertFalse(InvitationIdentity.isReserved(" invitation:INVN-X"))
    }

    @Test
    fun `a key version that is not v and one to three digits is refused at start-up`() {
        listOf("", "v", "v1234", "V1", "1", "v1 ", "v-1").forEach {
            assertThrows(IllegalArgumentException::class.java, { InvitationSettings(it) }, it)
        }
        listOf("v1", "v12", "v123").forEach { assertEquals(it, InvitationSettings(it).currentKeyVersion) }
    }
}
