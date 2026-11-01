package com.dynokore.gst.access.application.invitation

import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Instant

/** What the caller of reissueFirstAdministratorInvitation asks for; the tenant is the one the inbound adapter bound. */
data class ReissueFirstAdministratorInvitationCommand(
    val tenant: TenantRef,
    val actor: String,
    val invitationExpiresAt: Instant,
)

sealed interface ReissueFirstAdministratorInvitationOutcome {
    /** 200: the same PENDING grant with a new invitation; the old reference and code no longer match any row. */
    data class Reissued(
        val grantRef: String,
        val invitationRef: String,
        val invitationCode: String,
    ) : ReissueFirstAdministratorInvitationOutcome

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
        val live = invitations.liveTenantAdminGrants().singleOrNull()
        val oldRef = live?.invitationRef
        val expiry = live?.invitationExpiresAt
        if (live == null || live.status != PENDING || oldRef == null || expiry == null) {
            return ReissueFirstAdministratorInvitationOutcome.NotReissuable
        }
        // at the exact stored instant the invitation is not expired
        if (!expiry.isBefore(clock.instant())) return ReissueFirstAdministratorInvitationOutcome.NotReissuable
        val newRef = refs.next()
        check(newRef.length <= InvitationRefs.MAX_LENGTH) { "the invitation reference is too long" }
        val keyVersion = settings.currentKeyVersion
        // the code is derived before anything is written: an unreadable key stores nothing
        val code = codes.codeOf(command.tenant, newRef, keyVersion)
        invitations.reissue(live.grantRef, newRef, codes.digestOf(code), keyVersion, command.invitationExpiresAt)
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
