package com.dynokore.gst.access.application.invitation

import com.dynokore.gst.access.application.breakglass.SealedAccountKeeper
import com.dynokore.gst.access.application.breakglass.SealingCredential
import com.dynokore.gst.access.application.grant.AuthorityChangePublisher
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.application.grant.RoleGrantMapper
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

/** What the invited person sends: the reference, the code, and the `sub` of the verified token (never a field). */
data class AcceptFirstAdministratorCommand(
    val invitationRef: String,
    val invitationCode: String,
    val subject: String,
    val idempotencyKey: UUID,
)

/** What an acceptance reads and writes besides the grant: the invitation rows, the code digest, events and audit. */
class AcceptFirstAdministratorEffects(
    val invitations: FirstAdministratorInvitations,
    val codes: InvitationCodes,
    val publisher: AuthorityChangePublisher,
    val audit: GrantAudit,
    val accounts: SealedAccountKeeper,
)

sealed interface AcceptFirstAdministratorOutcome {
    /** 200: the grant is ACTIVE and bound to the subject. */
    data class Accepted(
        val grant: Grant,
        /** The initial sealing credential of the break-glass account this call created; shown once (INV-AC-8900). */
        val sealingCredential: SealingCredential?,
    ) : AcceptFirstAdministratorOutcome

    /** 200 again: the same key and fingerprint, grant ACTIVE and bound to the subject; nothing is written. */
    data class Replayed(
        val grant: Grant,
    ) : AcceptFirstAdministratorOutcome

    /** 422 DIFFERENT_REQUEST: the key of the stored acceptance is reused with another subject or code. */
    data object DifferentRequest : AcceptFirstAdministratorOutcome

    /** 404: a wrong code, an unknown reference and a reserved subject answer alike, so a reference cannot be probed. */
    data object NotFound : AcceptFirstAdministratorOutcome

    /** 409: the call instant is after the stored expiry. */
    data object Expired : AcceptFirstAdministratorOutcome

    /** 409: the invitation was accepted already. */
    data object AlreadyAccepted : AcceptFirstAdministratorOutcome

    /** 409: the grant was revoked (withdrawn); nothing is changed. */
    data object Withdrawn : AcceptFirstAdministratorOutcome

    /** 409: the subject already holds this role in this scope, so the grant cannot be bound to it. */
    data object SubjectAlreadyHolds : AcceptFirstAdministratorOutcome
}

/**
 * `acceptFirstAdministratorInvitation` (INV-FLOW-227 step 3; INV-Q-8185 A, INV-Q-14264 A): in the transaction of the
 * tenant that the inbound adapter found and bound, checks the code digest by constant-time equality, the expiry (not
 * expired at the exact stored instant; the instant is the clock port's) and the status; then upserts the identity
 * row of the subject, binds the PENDING grant to it and makes it ACTIVE, clears the digest so the code cannot be
 * used again, publishes AuthorityChanged as `grantRole` does, writes the audit entry and, when the tenant has no
 * sealed break-glass account yet, creates it with its initial sealing credential (INV-Q-8893 A); the digest is
 * stored and the credential is returned once. Every refusal leaves all rows as they were.
 */
class AcceptFirstAdministratorInvitation(
    private val tx: TransactionRunner,
    private val grants: GrantRepository,
    effects: AcceptFirstAdministratorEffects,
    private val clock: Clock,
) {
    private val records = RoleGrantMapper()
    private val invitations = effects.invitations
    private val codes = effects.codes
    private val publisher = effects.publisher
    private val audit = effects.audit
    private val accounts = effects.accounts

    fun handle(command: AcceptFirstAdministratorCommand): AcceptFirstAdministratorOutcome =
        if (InvitationIdentity.isReserved(command.subject)) {
            AcceptFirstAdministratorOutcome.NotFound
        } else {
            tx.inTransaction { accept(command) }
        }

    private fun accept(command: AcceptFirstAdministratorCommand): AcceptFirstAdministratorOutcome {
        invitations.lockTenant()
        val invitation = invitations.findByRef(command.invitationRef)
        val grant = invitation?.let { grants.find(GrantRef(it.grantRef)) }
        if (invitation == null || grant == null) return AcceptFirstAdministratorOutcome.NotFound
        return decide(command, invitation, grant)
    }

    private fun decide(
        command: AcceptFirstAdministratorCommand,
        invitation: InvitationRecord,
        grant: Grant,
    ): AcceptFirstAdministratorOutcome {
        val stored = invitation.codeDigest
        val presented = codes.digestOf(command.invitationCode)
        return when {
            // the key of the stored acceptance: a repeat (INV-Q-15347 A), admitted after the expiry
            invitation.acceptKey == command.idempotencyKey -> {
                repeat(command, invitation, grant, presented)
            }

            // a cleared digest means the invitation was accepted: the code is not compared again
            stored == null -> {
                if (grant.status == GrantStatus.ACTIVE) {
                    AcceptFirstAdministratorOutcome.AlreadyAccepted
                } else {
                    AcceptFirstAdministratorOutcome.NotFound
                }
            }

            !sameDigest(stored, presented) -> {
                AcceptFirstAdministratorOutcome.NotFound
            }

            grant.status == GrantStatus.REVOKED -> {
                AcceptFirstAdministratorOutcome.Withdrawn
            }

            grant.status != GrantStatus.PENDING -> {
                AcceptFirstAdministratorOutcome.AlreadyAccepted
            }

            // at the exact stored instant the invitation is not expired
            clock.instant().isAfter(invitation.expiresAt) -> {
                AcceptFirstAdministratorOutcome.Expired
            }

            else -> {
                bind(command, grant, fingerprintOf(command.subject, presented))
            }
        }
    }

    private fun repeat(
        command: AcceptFirstAdministratorCommand,
        invitation: InvitationRecord,
        grant: Grant,
        presented: String,
    ): AcceptFirstAdministratorOutcome =
        when {
            !sameDigest(invitation.acceptFingerprint.orEmpty(), fingerprintOf(command.subject, presented)) -> {
                AcceptFirstAdministratorOutcome.DifferentRequest
            }

            grant.status == GrantStatus.ACTIVE && grant.identity.value == command.subject -> {
                AcceptFirstAdministratorOutcome.Replayed(grant)
            }

            grant.status == GrantStatus.REVOKED -> {
                AcceptFirstAdministratorOutcome.Withdrawn
            }

            else -> {
                AcceptFirstAdministratorOutcome.AlreadyAccepted
            }
        }

    private fun fingerprintOf(
        subject: String,
        digest: String,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$subject:$digest".toByteArray(StandardCharsets.UTF_8))
            .let {
                java.util.HexFormat
                    .of()
                    .formatHex(it)
            }

    private fun bind(
        command: AcceptFirstAdministratorCommand,
        pending: Grant,
        fingerprint: String,
    ): AcceptFirstAdministratorOutcome {
        val identity = IdentityRef(command.subject)
        grants.lockIdentity(identity, GranteeKind.STAFF)
        val held = grants.findByRepeatKey(identity, pending.role.wire, pending.scope)
        if (held != null && held.status != GrantStatus.REVOKED) {
            return AcceptFirstAdministratorOutcome.SubjectAlreadyHolds
        }
        val active = pending.accepted(identity)
        grants.update(pending, active)
        invitations.markAccepted(command.invitationRef, command.idempotencyKey, fingerprint)
        publisher.publish(
            records.forChange(active, grants::nextAuthorityVersion),
        )
        audit.record(
            GrantAuditEntry(
                actor = command.subject,
                action = "acceptFirstAdministratorInvitation",
                scope = "${active.scope.level}:${RoleGrantMapper.scopeRefOf(active.scope)}",
                reason = null,
                priorStateJson = priorState(pending),
            ),
        )
        // the sealed account is written in this transaction, so a failure here leaves the grant PENDING (INV-AC-8898)
        return AcceptFirstAdministratorOutcome.Accepted(active, accounts.createInitial())
    }

    private fun priorState(pending: Grant): String =
        """{"grantRef":"${pending.ref.value}","status":"PENDING","identity":"${pending.identity.value}"}"""

    private fun sameDigest(
        stored: String,
        presented: String,
    ): Boolean =
        MessageDigest.isEqual(
            stored.toByteArray(StandardCharsets.UTF_8),
            presented.toByteArray(StandardCharsets.UTF_8),
        )
}
