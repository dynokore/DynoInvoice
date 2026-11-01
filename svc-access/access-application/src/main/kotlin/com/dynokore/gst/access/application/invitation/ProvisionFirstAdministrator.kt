package com.dynokore.gst.access.application.invitation

import com.dynokore.gst.access.application.grant.AuthorityRecords
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.grant.GrantRepository
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.gst.access.domain.grant.GrantHeader
import com.dynokore.gst.access.domain.grant.GrantSource
import com.dynokore.gst.access.domain.grant.GrantState
import com.dynokore.gst.access.domain.grant.GrantStatus
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** What a provisioning writes and derives besides the grant: the invitation rows, code, reference and audit. */
class FirstAdministratorEffects(
    val invitations: FirstAdministratorInvitations,
    val codes: InvitationCodes,
    val refs: InvitationRefs,
    val audit: GrantAudit,
)

/** What the caller of provisionFirstAdministrator asks for; the tenant is the one the inbound adapter bound. */
data class ProvisionFirstAdministratorCommand(
    val tenant: TenantRef,
    val actor: String,
    val idempotencyKey: UUID,
    val administratorEmail: String,
    val invitationExpiresAt: Instant,
)

sealed interface ProvisionFirstAdministratorOutcome {
    /** 201: the pending grant and its invitation; [repeated] is true for a repeat of the same key and request. */
    data class Provisioned(
        val grantRef: String,
        val invitationRef: String,
        val invitationCode: String,
        val repeated: Boolean,
    ) : ProvisionFirstAdministratorOutcome

    /** 409: the tenant holds a `tenant.admin` grant that is ACTIVE or PENDING. */
    data object TenantHasAdministrator : ProvisionFirstAdministratorOutcome

    /** A repeat of the original key after a reissue: the invitation that call issued is void (INV-Q-15861 A). */
    data object InvitationReissued : ProvisionFirstAdministratorOutcome

    /** The same key with another request (API-15). */
    data object DifferentRequest : ProvisionFirstAdministratorOutcome

    /** The secret of the code key is missing or unreadable (fail closed). */
    data object CodeKeyUnavailable : ProvisionFirstAdministratorOutcome
}

/**
 * `provisionFirstAdministrator` and `provisionInstallationFirstAdministrator` (INV-Q-8185 A, INV-Q-14261 A,
 * INV-Q-14264 A): stores one PENDING Tenant-scope `tenant.admin` grant for an invited person, the placeholder identity
 * `invitation:<invitationRef>` it is given to, and the invitation row, when the tenant holds no `tenant.admin` grant
 * that is ACTIVE or PENDING. The tenant is locked first, so two calls for one tenant are decided one after the other.
 * The code is derived from the invitation reference ([InvitationCodes]); only its digest is stored. The caller check is
 * generated from the contract by service-runtime; nothing here names a caller.
 */
class ProvisionFirstAdministrator(
    private val tx: TransactionRunner,
    private val grants: GrantRepository,
    effects: FirstAdministratorEffects,
    private val clock: Clock,
    private val settings: InvitationSettings,
) {
    private val invitations = effects.invitations
    private val codes = effects.codes
    private val refs = effects.refs
    private val audit = effects.audit

    fun handle(command: ProvisionFirstAdministratorCommand): ProvisionFirstAdministratorOutcome =
        try {
            tx.inTransaction { provision(command) }
        } catch (_: InvitationKeyUnavailable) {
            ProvisionFirstAdministratorOutcome.CodeKeyUnavailable
        }

    private fun provision(command: ProvisionFirstAdministratorCommand): ProvisionFirstAdministratorOutcome {
        invitations.lockTenant()
        val fingerprint = fingerprintOf(command)
        val existing = invitations.findByKey(command.idempotencyKey)
        return when {
            existing != null -> repeat(command, existing, fingerprint)
            invitations.holdsLiveTenantAdminGrant() -> ProvisionFirstAdministratorOutcome.TenantHasAdministrator
            else -> first(command, fingerprint)
        }
    }

    private fun repeat(
        command: ProvisionFirstAdministratorCommand,
        existing: StoredInvitation,
        fingerprint: String,
    ): ProvisionFirstAdministratorOutcome {
        if (existing.requestFingerprint != fingerprint) return ProvisionFirstAdministratorOutcome.DifferentRequest
        if (existing.reissued) return ProvisionFirstAdministratorOutcome.InvitationReissued
        return ProvisionFirstAdministratorOutcome.Provisioned(
            grantRef = existing.grantRef,
            invitationRef = existing.invitationRef,
            invitationCode = codes.codeOf(command.tenant, existing.invitationRef, existing.codeKeyVersion),
            repeated = true,
        )
    }

    private fun first(
        command: ProvisionFirstAdministratorCommand,
        fingerprint: String,
    ): ProvisionFirstAdministratorOutcome {
        val invitationRef = refs.next()
        check(invitationRef.length <= InvitationRefs.MAX_LENGTH) { "the invitation reference is too long" }
        val keyVersion = settings.currentKeyVersion
        // the code is derived before anything is written: an unreadable key stores nothing
        val code = codes.codeOf(command.tenant, invitationRef, keyVersion)
        val identity = IdentityRef(InvitationIdentity.PREFIX + invitationRef)
        grants.lockIdentity(identity, GranteeKind.EXTERNAL)
        val grant =
            Grant.issue(
                GrantHeader(grants.nextGrantRef(), command.tenant, identity),
                Role.TENANT_ADMIN,
                Scope.Tenant(command.tenant),
                ValidityWindow(clock.instant(), null),
                GrantState(GrantStatus.PENDING, GrantSource.BY_HAND, 0),
            )
        grants.insert(grant)
        invitations.insert(
            NewInvitation(
                invitationRef = invitationRef,
                grantRef = grant.ref.value,
                invitedEmail = command.administratorEmail,
                expiresAt = command.invitationExpiresAt,
                codeDigest = codes.digestOf(code),
                codeKeyVersion = keyVersion,
                provisioningKey = command.idempotencyKey,
                requestFingerprint = fingerprint,
            ),
        )
        audit.record(
            GrantAuditEntry(
                actor = command.actor,
                action = "provisionFirstAdministrator",
                scope = "${grant.scope.level}:${AuthorityRecords.scopeRefOf(grant.scope)}",
                reason = null,
                priorStateJson = null,
            ),
        )
        return ProvisionFirstAdministratorOutcome.Provisioned(grant.ref.value, invitationRef, code, repeated = false)
    }

    private fun fingerprintOf(command: ProvisionFirstAdministratorCommand): String =
        HexFormat.of().formatHex(
            MessageDigest
                .getInstance("SHA-256")
                .digest(
                    listOf(command.tenant.value, command.administratorEmail, command.invitationExpiresAt.toString())
                        .joinToString("\n")
                        .toByteArray(StandardCharsets.UTF_8),
                ),
        )
}
