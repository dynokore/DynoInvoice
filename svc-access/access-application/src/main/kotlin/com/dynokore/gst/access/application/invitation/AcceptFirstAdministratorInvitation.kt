package com.dynokore.gst.access.application.invitation

import com.dynokore.gst.access.application.grant.AuthorityChangePublisher
import com.dynokore.gst.access.application.grant.AuthorityRecords
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock

/** What the invited person sends: the reference, the code, and the `sub` of the verified token (never a field). */
data class AcceptFirstAdministratorCommand(
    val invitationRef: String,
    val invitationCode: String,
    val subject: String,
)

/** What an acceptance reads and writes besides the grant: the invitation rows, the code digest, events and audit. */
class AcceptFirstAdministratorEffects(
    val invitations: FirstAdministratorInvitations,
    val codes: InvitationCodes,
    val publisher: AuthorityChangePublisher,
    val audit: GrantAudit,
)

sealed interface AcceptFirstAdministratorOutcome {
    /** 200: the grant is ACTIVE and bound to the subject. */
    data class Accepted(
        val grant: Grant,
    ) : AcceptFirstAdministratorOutcome

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
 * used again, publishes AuthorityChanged as `grantRole` does and writes the audit entry. Every refusal leaves all
 * rows as they were.
 */
class AcceptFirstAdministratorInvitation(
    private val tx: TransactionRunner,
    private val grants: GrantRepository,
    effects: AcceptFirstAdministratorEffects,
    directory: EntityDirectory,
    matrix: PermissionMatrix,
    private val clock: Clock,
) {
    private val records = AuthorityRecords(directory, matrix)
    private val invitations = effects.invitations
    private val codes = effects.codes
    private val publisher = effects.publisher
    private val audit = effects.audit

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
        return when {
            // a cleared digest means the invitation was accepted: the code is not compared again
            stored == null -> {
                if (grant.status == GrantStatus.ACTIVE) {
                    AcceptFirstAdministratorOutcome.AlreadyAccepted
                } else {
                    AcceptFirstAdministratorOutcome.NotFound
                }
            }

            !sameDigest(stored, codes.digestOf(command.invitationCode)) -> {
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
                bind(command, grant)
            }
        }
    }

    private fun bind(
        command: AcceptFirstAdministratorCommand,
        pending: Grant,
    ): AcceptFirstAdministratorOutcome {
        val identity = IdentityRef(command.subject)
        grants.lockIdentity(identity, GranteeKind.STAFF)
        val held = grants.findByRepeatKey(identity, pending.role.wire, pending.scope)
        if (held != null && held.status != GrantStatus.REVOKED) {
            return AcceptFirstAdministratorOutcome.SubjectAlreadyHolds
        }
        val active = pending.accepted(identity)
        grants.update(pending, active)
        invitations.clearCodeDigest(command.invitationRef)
        publisher.publish(
            records.forChange(
                active.tenant,
                identity,
                active,
                grants.findByIdentity(identity),
                grants::nextAuthorityVersion,
            ),
        )
        audit.record(
            GrantAuditEntry(
                actor = command.subject,
                action = "acceptFirstAdministratorInvitation",
                scope = "${active.scope.level}:${AuthorityRecords.scopeRefOf(active.scope)}",
                reason = null,
                priorStateJson = priorState(pending),
            ),
        )
        return AcceptFirstAdministratorOutcome.Accepted(active)
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
