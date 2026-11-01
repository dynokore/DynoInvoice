package com.dynokore.gst.access.application.invitation

import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** What the caller of reissueFirstAdministratorInvitation asks for; the tenant is the one the inbound adapter bound. */
data class ReissueFirstAdministratorInvitationCommand(
    val tenant: TenantRef,
    val actor: String,
    val idempotencyKey: UUID,
    val invitationExpiresAt: Instant,
)

sealed interface ReissueFirstAdministratorInvitationOutcome {
    /** 200: the same PENDING grant with a new invitation; the old reference and code no longer match any row. */
    data class Reissued(
        val grantRef: String,
        val invitationRef: String,
        val invitationCode: String,
    ) : ReissueFirstAdministratorInvitationOutcome

    /** 422: the same key with another request (API-15); nothing is stored. */
    data object DifferentRequest : ReissueFirstAdministratorInvitationOutcome

    /** 409: the tenant is not in the one state a reissue is allowed in; nothing is stored. */
    data object NotReissuable : ReissueFirstAdministratorInvitationOutcome

    /** The secret of the code key is missing or unreadable (fail closed). */
    data object CodeKeyUnavailable : ReissueFirstAdministratorInvitationOutcome
}

/**
 * `reissueFirstAdministratorInvitation` (INV-Q-8232 A, INV-FLOW-227 alternate): when the only `tenant.admin` grant of
 * the tenant that is ACTIVE or PENDING is one PENDING grant whose stored invitation expiry is before the call instant
 * (the clock port's, never the request's; at the exact expiry the call is 409, as an acceptance still succeeds then),
 * it gives that grant a new invitation reference, a new code (only its digest is stored) and the requested expiry. No
 * grant row is inserted or changed. The tenant is locked first, so two calls (and a provisioning or an acceptance) for
 * one tenant are decided one after the other. The caller check is generated from the contract by service-runtime.
 */
class ReissueFirstAdministratorInvitation(
    private val tx: TransactionRunner,
    effects: FirstAdministratorEffects,
    private val clock: Clock,
    private val settings: InvitationSettings,
) {
    private val invitations = effects.invitations
    private val codes = effects.codes
    private val refs = effects.refs
    private val audit = effects.audit

    fun handle(command: ReissueFirstAdministratorInvitationCommand): ReissueFirstAdministratorInvitationOutcome =
        try {
            tx.inTransaction { reissue(command) }
        } catch (_: InvitationKeyUnavailable) {
            ReissueFirstAdministratorInvitationOutcome.CodeKeyUnavailable
        }

    private fun reissue(
        command: ReissueFirstAdministratorInvitationCommand,
    ): ReissueFirstAdministratorInvitationOutcome {
        invitations.lockTenant()
        val fingerprint = fingerprintOf(command)
        val stored = invitations.findByReissueKey(command.idempotencyKey)
        if (stored != null) return repeat(command, stored, fingerprint)
        val live = invitations.liveTenantAdminGrants().singleOrNull()
        return if (live != null && isExpiredPending(live)) {
            renew(command, live, checkNotNull(live.invitationRef), fingerprint)
        } else {
            ReissueFirstAdministratorInvitationOutcome.NotReissuable
        }
    }

    /** A repeat of the latest reissue key: the stored data again, whatever the grant, expiry or clock are now. */
    private fun repeat(
        command: ReissueFirstAdministratorInvitationCommand,
        stored: StoredReissue,
        fingerprint: String,
    ): ReissueFirstAdministratorInvitationOutcome {
        if (stored.reissueFingerprint != fingerprint) return ReissueFirstAdministratorInvitationOutcome.DifferentRequest
        return ReissueFirstAdministratorInvitationOutcome.Reissued(
            grantRef = stored.grantRef,
            invitationRef = stored.invitationRef,
            invitationCode = codes.codeOf(command.tenant, stored.invitationRef, stored.codeKeyVersion),
        )
    }

    private fun fingerprintOf(command: ReissueFirstAdministratorInvitationCommand): String =
        HexFormat.of().formatHex(
            MessageDigest
                .getInstance("SHA-256")
                .digest(
                    "${command.tenant.value}:${command.invitationExpiresAt}".toByteArray(StandardCharsets.UTF_8),
                ),
        )

    /** A PENDING grant with an invitation whose stored expiry is before the call instant (at the exact instant: no). */
    private fun isExpiredPending(live: LiveAdminGrant): Boolean {
        val expiry = live.invitationExpiresAt
        return live.status == PENDING && live.invitationRef != null && expiry?.isBefore(clock.instant()) == true
    }

    private fun renew(
        command: ReissueFirstAdministratorInvitationCommand,
        live: LiveAdminGrant,
        oldRef: String,
        fingerprint: String,
    ): ReissueFirstAdministratorInvitationOutcome {
        val newRef = refs.next()
        check(newRef.length <= InvitationRefs.MAX_LENGTH) { "the invitation reference is too long" }
        val keyVersion = settings.currentKeyVersion
        // the code is derived before anything is written: an unreadable key stores nothing
        val code = codes.codeOf(command.tenant, newRef, keyVersion)
        invitations.reissue(
            live.grantRef,
            InvitationReplacement(
                newRef,
                codes.digestOf(code),
                keyVersion,
                command.invitationExpiresAt,
                command.idempotencyKey,
                fingerprint,
            ),
        )
        audit.record(
            GrantAuditEntry(
                actor = command.actor,
                action = "reissueFirstAdministratorInvitation",
                scope = "TENANT:${command.tenant.value}",
                reason = null,
                priorStateJson =
                    """{"tenantRef":"${command.tenant.value}","grantRef":"${live.grantRef}",""" +
                        """"oldInvitationRef":"$oldRef","newInvitationRef":"$newRef"}""",
            ),
        )
        return ReissueFirstAdministratorInvitationOutcome.Reissued(live.grantRef, newRef, code)
    }

    private companion object {
        const val PENDING = "PENDING"
    }
}
