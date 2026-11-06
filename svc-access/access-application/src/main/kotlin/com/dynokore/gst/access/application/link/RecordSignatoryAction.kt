package com.dynokore.gst.access.application.link

import com.dynokore.gst.access.application.approval.RecordsMappingAcknowledgements
import com.dynokore.gst.access.application.consent.ConsentRepository
import com.dynokore.gst.access.application.consent.ConsentWithdrawal
import com.dynokore.gst.access.application.consent.WithdrawConsentOutcome
import com.dynokore.gst.access.application.grant.EntityDirectory
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.domain.consent.ConsentStatus
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.Target
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.link.LinkRef
import com.dynokore.gst.access.domain.link.SignedLink
import com.dynokore.gst.access.domain.role.Allowance
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Instant

enum class SignatoryActionKind { ACKNOWLEDGE, QUERY, WITHDRAW }

/** One signatory action stored as evidence; [actor] is always `link:<linkRef>`, never a role. */
data class SignatoryAction(
    val linkRef: LinkRef,
    val kind: SignatoryActionKind,
    /** Empty for WITHDRAW. */
    val packVersion: String,
    val queryText: String?,
    val client: EntityRef,
    val actor: String,
    val recordedAt: Instant,
)

interface SignatoryActions {
    /** The action stored for exactly (link, kind, pack version), or null: the repeat key of the contract. */
    fun find(
        linkRef: LinkRef,
        kind: SignatoryActionKind,
        packVersion: String,
    ): SignatoryAction?

    fun insert(action: SignatoryAction)
}

/**
 * The scoped key decision for the principal `link:<linkRef>` (rulings INV-Q-17199 A, INV-Q-9222 A): access-svc answers
 * from its own link store. While the link's row is verified, not expired, not invalidated and not revoked, the
 * principal holds the role client.signatory at the scope of the link's bound entity (its client entity, otherwise
 * the envelope entity of its origin event), and the catalogue's matrix decides the key for exactly that entity. No code here compares a role name (INV-Q-10027).
 */
class LinkPermissions(
    private val matrix: PermissionMatrix,
    private val directory: EntityDirectory,
) {
    fun allows(
        link: SignedLink,
        permission: Permission,
        now: Instant,
    ): Boolean {
        val bound = link.boundEntity
        val membership = directory.membership(bound)
        val cell = matrix.allowance(LINK_PRINCIPAL_ROLE, permission)
        return membership != null && link.sessionOpen(now) &&
            cell in setOf(Allowance.PERMITTED, Allowance.WITHIN_SCOPE) &&
            Scope.Entity(bound).covers(Target(bound), membership)
    }

    companion object {
        /** The role every verified link principal holds, at its client entity (INV-Q-9222 A). */
        val LINK_PRINCIPAL_ROLE = Role.CLIENT_SIGNATORY
        val CONSENT_WITHDRAW = Permission("consent.withdraw")
        val MAPPING_EFFECT_ACKNOWLEDGE = Permission("mapping-effect.acknowledge")
    }
}

data class SignatoryActionCommand(
    val tenant: TenantRef,
    val linkRef: LinkRef,
    val kind: SignatoryActionKind,
    val packVersion: String?,
    val queryText: String?,
    /** A request that carries either of these is invalid: the consent and client entity come from the link record. */
    val consentRef: String? = null,
    val clientRef: String? = null,
)

sealed interface SignatoryActionOutcome {
    data class Recorded(
        val action: SignatoryAction,
        val repeated: Boolean,
    ) : SignatoryActionOutcome

    /** Unknown, expired or not yet verified link: one answer for the three. */
    data object Unauthenticated : SignatoryActionOutcome

    /** The link was invalidated or revoked (ACC-004). */
    data object Invalidated : SignatoryActionOutcome

    /** The scoped key is not allowed to the link at its bound entity, or no membership exists for it. */
    data object Forbidden : SignatoryActionOutcome

    /** The consent of the link is not live. */
    data object ConsentNotLive : SignatoryActionOutcome

    data class Invalid(
        val reason: String,
    ) : SignatoryActionOutcome
}

/**
 * `recordSignatoryAction` (INV-REQ-900, INV-REQ-1010): records ACKNOWLEDGE, QUERY or WITHDRAW as evidence with the
 * actor `link:<linkRef>`. The link row is locked first; the consent, client entity and actor come from that record
 * and never from the request.
 */
@Suppress("LongParameterList") // one collaborator per port
class RecordSignatoryActionUseCase(
    private val tx: TransactionRunner,
    private val links: SignedLinks,
    private val permissions: LinkPermissions,
    private val actions: SignatoryActions,
    private val consents: ConsentRepository,
    private val withdrawal: ConsentWithdrawal,
    private val acknowledgements: RecordsMappingAcknowledgements,
    private val audit: GrantAudit,
    private val clock: Clock,
) {
    @Suppress("ReturnCount")
    fun handle(command: SignatoryActionCommand): SignatoryActionOutcome =
        tx.inTransaction {
            val link = links.lock(command.linkRef) ?: return@inTransaction SignatoryActionOutcome.Unauthenticated
            val now = clock.instant()
            if (!link.usable) return@inTransaction SignatoryActionOutcome.Invalidated
            if (!link.sessionOpen(now)) return@inTransaction SignatoryActionOutcome.Unauthenticated
            invalidInput(command)?.let { return@inTransaction SignatoryActionOutcome.Invalid(it) }
            val key =
                if (command.kind == SignatoryActionKind.WITHDRAW) {
                    LinkPermissions.CONSENT_WITHDRAW
                } else {
                    LinkPermissions.MAPPING_EFFECT_ACKNOWLEDGE
                }
            if (!permissions.allows(link, key, now)) return@inTransaction SignatoryActionOutcome.Forbidden
            val client = link.boundEntity
            val pack = command.packVersion.orEmpty()
            actions.find(link.ref, command.kind, pack)?.let {
                return@inTransaction SignatoryActionOutcome.Recorded(it, repeated = true)
            }
            if (command.kind == SignatoryActionKind.WITHDRAW && !withdrawLinkConsent(command.tenant, link, client)) {
                return@inTransaction SignatoryActionOutcome.ConsentNotLive
            }
            val actor = VerifySignatoryLinkUseCase.actorOf(link.ref)
            if (command.kind == SignatoryActionKind.ACKNOWLEDGE) {
                acknowledgements.record(pack, client.value, actor, now)
            }
            val action = SignatoryAction(link.ref, command.kind, pack, command.queryText, client, actor, now)
            actions.insert(action)
            audit.record(
                GrantAuditEntry(
                    actor = actor,
                    action = "signatoryAction:${command.kind.name}",
                    scope = "entity:${client.value}",
                    reason = null,
                    priorStateJson = null,
                ),
            )
            SignatoryActionOutcome.Recorded(action, repeated = false)
        }

    private fun invalidInput(command: SignatoryActionCommand): String? =
        when {
            command.consentRef != null || command.clientRef != null -> {
                "consentRef and clientRef are never request fields"
            }

            command.kind != SignatoryActionKind.WITHDRAW && command.packVersion.isNullOrBlank() -> {
                "packVersion is required"
            }

            command.kind == SignatoryActionKind.QUERY && command.queryText.isNullOrBlank() -> {
                "queryText is required"
            }

            command.kind == SignatoryActionKind.WITHDRAW && command.packVersion != null -> {
                "packVersion is for ACKNOWLEDGE and QUERY"
            }

            else -> {
                null
            }
        }

    /** Withdraws exactly the consent the link names; false when it is absent, of another client, or not live. */
    private fun withdrawLinkConsent(
        tenant: TenantRef,
        link: SignedLink,
        client: EntityRef,
    ): Boolean {
        // a link that names no consent is answered like one whose consent is not live, with no read at all
        val named = link.consent ?: return false
        // read through the link's own bound entity, so a consent of another client entity is never read
        val live = consents.findLiveForClient(client)?.takeIf { it.ref == named }
        val consent = live?.let { consents.lock(it.ref) }?.takeIf { it.status == ConsentStatus.LIVE }
        return consent != null &&
            withdrawal.perform(tenant, VerifySignatoryLinkUseCase.actorOf(link.ref), consent) is
                WithdrawConsentOutcome.Withdrawn
    }
}
