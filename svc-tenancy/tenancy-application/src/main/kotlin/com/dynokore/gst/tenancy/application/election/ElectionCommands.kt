package com.dynokore.gst.tenancy.application.election

import com.dynokore.gst.tenancy.application.status.ChangedAttribute
import com.dynokore.gst.tenancy.application.status.EntityProfileChange
import com.dynokore.gst.tenancy.application.status.EntityProfileChangePublisher
import com.dynokore.gst.tenancy.application.status.EntityStatusStore
import com.dynokore.gst.tenancy.application.structure.ProfileEntry
import com.dynokore.gst.tenancy.application.structure.StructureStore
import com.dynokore.gst.tenancy.application.structure.entityNode
import com.dynokore.gst.tenancy.domain.ElectionKeys
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.gst.tenancy.domain.ElectionPermissions
import com.dynokore.gst.tenancy.domain.ElectionRoles
import com.dynokore.gst.tenancy.domain.FactAnswer
import com.dynokore.gst.tenancy.domain.IssuanceMode
import com.dynokore.gst.tenancy.domain.StatusTimeline
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.LocalDate

/** The profile attribute the issuance mode is published under on EntityProfileChanged (INV-Q-4993 B). */
const val ISSUANCE_MODE_ATTRIBUTE = "issuanceMode"

sealed interface ElectionOutcome {
    /** [repeated] is true when the same election was stored for the same date before and nothing was written. */
    data class Done(
        val election: ElectionRecord,
        val repeated: Boolean = false,
    ) : ElectionOutcome

    /** 202: a change of an election already recorded waits for the approval of another person (INV-Q-10105). */
    data class Pending(
        val change: PendingChange,
    ) : ElectionOutcome

    /** 200: the pending change was approved and the election is in force from its effective date. */
    data class Approved(
        val approval: ApprovedChange,
    ) : ElectionOutcome

    /** 404 SRT-005: no such entity, or no PENDING change of that reference on it. */
    data object NotFound : ElectionOutcome

    /** 400 SRT-001: a missing or unknown value. */
    data class Invalid(
        val reason: String,
    ) : ElectionOutcome

    /** 422 POL-014: a value refused by a check; nothing is stored or published. */
    data class Refused(
        val reason: String,
    ) : ElectionOutcome

    /** 403 SRT-004: a caller or confirmer who lacks the role, is the caller or cannot be proven. */
    data class Forbidden(
        val reason: String,
    ) : ElectionOutcome
}

/**
 * setIssuanceMode, recordElection, electRateSource and approveElectionChange (INV-REQ-323, INV-REQ-422,
 * INV-REQ-505, INV-REQ-1283 to INV-REQ-1286): each stores the election effective-dated with its actor through the
 * election store, after the checks of the rulings INV-Q-9692, INV-Q-9694, INV-Q-9698, INV-Q-9702, INV-Q-9704 and
 * INV-Q-9706. Nothing is defaulted. A change of an election already recorded is stored PENDING and takes effect
 * only when another person holding `election-change.approve` approves it (INV-Q-10105).
@Suppress("LongParameterList", "TooManyFunctions") // the ports and checks of the three operations
class ElectionCommands(
    private val tx: TransactionRunner,
    private val structure: StructureStore,
    private val elections: ElectionPolicyStore,
    private val statuses: EntityStatusStore,
    private val publisher: EntityProfileChangePublisher,
    private val facts: EntityFactSource,
    private val routes: AccessPointRouteStore,
    private val scopePacks: ScopePackStore,
    private val authority: ActionAuthority,
    private val caller: CallerSource,
    private val changes: ElectionChangeStore,
    private val audit: ElectionAudit,
) {
    /** Elects the Type 1A issuance mode from [effectiveFrom]; the second confirmation is required every time. */
    fun setIssuanceMode(
        tenant: TenantId,
        entityRef: String,
        mode: IssuanceMode,
        effectiveFrom: LocalDate,
        secondConfirmationBy: String?,
    ): ElectionOutcome =
        tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction ElectionOutcome.NotFound
            val actor = caller.actor()
            gate(actor, entity.ref)?.let { return@inTransaction it }
            if (secondConfirmationBy.isNullOrEmpty()) {
                return@inTransaction ElectionOutcome.Invalid("secondConfirmationBy is required")
            }
            person(secondConfirmationBy, actor, "confirmer", entity.ref, ElectionPermissions.CONFIRM) {
                authority.holds(secondConfirmationBy, entity.ref, ElectionPermissions.CONFIRM)
            }?.let { return@inTransaction it }
            val before = elections.history(entity.ref, ElectionKind.ISSUANCE_MODE)
            if (mode == IssuanceMode.OBSERVE) {
                observeRefusal(entity.ref)?.let { return@inTransaction it }
            }
            repeated(before, mode.name, effectiveFrom)?.let { return@inTransaction it }
            val reason = "VP-25 election by $actor, second confirmation by $secondConfirmationBy"
            store(
                entity.ref,
                entity.groupRef,
                ElectionKind.ISSUANCE_MODE,
                mode.name,
                null,
                effectiveFrom,
                actor,
                reason,
            )?.let { return@inTransaction it }
            publishIssuanceMode(tenant, entity.ref, before, mode, effectiveFrom)
            ElectionOutcome.Done(
                elections.history(entity.ref, ElectionKind.ISSUANCE_MODE).single { it.effectiveFrom == effectiveFrom },
            )
        }

    /** Elects the purchase date basis (VP-22) or the apportionment mode (VP-23), exactly one of the four keys. */
    fun recordElection(
        entityRef: String,
        election: String,
        effectiveFrom: LocalDate,
    ): ElectionOutcome =
        tx.inTransaction {
            val kind =
                ElectionKeys.recordableKind(election)
                    ?: return@inTransaction ElectionOutcome.Invalid(
                        "election must be one of INVOICE_DATE, POSTING_DATE, CLAIMABLE_PORTION, ENTIRE_INVOICE",
                    )
            val entity = structure.entity(entityRef) ?: return@inTransaction ElectionOutcome.NotFound
            val actor = caller.actor()
            gate(actor, entity.ref)?.let { return@inTransaction it }
            if (election == ElectionKeys.POSTING_DATE) {
                postingRefusal(entity.ref)?.let { return@inTransaction it }
            }
            val before = elections.history(entity.ref, kind)
            repeated(before, election, effectiveFrom)?.let { return@inTransaction it }
            if (isChange(before, election, null)) {
                return@inTransaction request(entity.ref, kind, election, null, effectiveFrom, actor)
            }
            store(
                entity.ref,
                entity.groupRef,
                kind,
                election,
                null,
                effectiveFrom,
                actor,
                "${kind.variationPoint} election by $actor",
            )?.let { return@inTransaction it }
            ElectionOutcome.Done(elections.history(entity.ref, kind).single { it.effectiveFrom == effectiveFrom })
        }

    /** Elects the exchange-rate source and basis (VP-24) from the scope pack's approved list. */
    fun electRateSource(
        entityRef: String,
        source: String,
        basis: String,
        effectiveFrom: LocalDate,
    ): ElectionOutcome =
        tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction ElectionOutcome.NotFound
            val actor = caller.actor()
            gate(actor, entity.ref)?.let { return@inTransaction it }
            listedRefusal(source, basis, effectiveFrom)?.let { return@inTransaction it }
            val before = elections.history(entity.ref, ElectionKind.EXCHANGE_RATE_SOURCE)
            before
                .firstOrNull { it.effectiveFrom == effectiveFrom && it.value == source && it.basis == basis }
                ?.let { return@inTransaction ElectionOutcome.Done(it, repeated = true) }
            if (isChange(before, source, basis)) {
                return@inTransaction request(
                    entity.ref,
                    ElectionKind.EXCHANGE_RATE_SOURCE,
                    source,
                    basis,
                    effectiveFrom,
                    actor,
                )
            }
            store(
                entity.ref,
                entity.groupRef,
                ElectionKind.EXCHANGE_RATE_SOURCE,
                source,
                basis,
                effectiveFrom,
                actor,
                "${ElectionKind.EXCHANGE_RATE_SOURCE.variationPoint} election by $actor",
            )?.let { return@inTransaction it }
            ElectionOutcome.Done(
                elections
                    .history(entity.ref, ElectionKind.EXCHANGE_RATE_SOURCE)
                    .single { it.effectiveFrom == effectiveFrom },
            )
        }

    /**
     * Approves a PENDING change (INV-Q-10105): the caller holds `election-change.approve` on the entity and is not the
     * requester; the value checks of the request are run again; the version is written with the requester as actor and
     * the approver in the reason, and the change turns APPROVED.
     */
    fun approveElectionChange(
        entityRef: String,
        changeRef: String,
    ): ElectionOutcome =
        tx.inTransaction {
            val entity = structure.entity(entityRef) ?: return@inTransaction ElectionOutcome.NotFound
            val approver = caller.actor()
            if (!holds { authority.holds(approver, entity.ref, ElectionPermissions.APPROVE) }) {
                return@inTransaction ElectionOutcome.Forbidden(
                    "$approver does not hold ${ElectionPermissions.APPROVE} for entity ${entity.ref}",
                )
            }
            val change = changes.pending(entity.ref, changeRef) ?: return@inTransaction ElectionOutcome.NotFound
            if (change.requestedBy == approver) {
                return@inTransaction ElectionOutcome.Forbidden("the approver must be a person other than the requester")
            }
            valueRefusal(change)?.let { return@inTransaction it }
            store(
                entity.ref,
                entity.groupRef,
                change.kind,
                change.value,
                change.basis,
                change.effectiveFrom,
                change.requestedBy,
                "${change.kind.variationPoint} election change ${change.changeRef} requested by " +
                    "${change.requestedBy}, approved by $approver",
            )?.let { return@inTransaction it }
            val approval =
                checkNotNull(changes.approve(entity.ref, change.changeRef, approver)) { "change not PENDING" }
            audit.approved(approval)
            ElectionOutcome.Approved(approval)
        }

    /** The value checks of the original request, run again at approval time (INV-REQ-1283, INV-REQ-504). */
    private fun valueRefusal(change: PendingChange): ElectionOutcome.Refused? =
        when {
            change.kind == ElectionKind.PURCHASE_DATE_BASIS && change.value == ElectionKeys.POSTING_DATE -> {
                postingRefusal(change.entityRef)
            }

            change.kind == ElectionKind.EXCHANGE_RATE_SOURCE -> {
                listedRefusal(change.value, checkNotNull(change.basis), change.effectiveFrom)
            }

            else -> {
                null
            }
        }

    /** Stores the change PENDING and writes the "requested" audit entry; nothing reaches the policy writer. */
    @Suppress("LongParameterList") // one request with its requester
    private fun request(
        entityRef: String,
        kind: ElectionKind,
        value: String,
        basis: String?,
        effectiveFrom: LocalDate,
        actor: String,
    ): ElectionOutcome {
        val change = changes.request(entityRef, kind, value, basis, effectiveFrom, actor)
        audit.requested(change)
        return ElectionOutcome.Pending(change)
    }

    /** The caller must hold `entity-config.manage` (entity.admin only) on the entity (ruling INV-Q-10025). */
    private fun gate(
        actor: String,
        entityRef: String,
    ): ElectionOutcome.Forbidden? =
        if (holds { authority.holds(actor, entityRef, ElectionPermissions.MANAGE) }) {
            null
        } else {
            ElectionOutcome.Forbidden("$actor does not hold ${ElectionPermissions.MANAGE} for entity $entityRef")
        }

    /** A check that throws cannot prove the person holds the grant: fail closed. */
    private fun holds(check: () -> Boolean): Boolean =
        try {
            check()
        } catch (_: RuntimeException) {
            false
        }

    /** The second person (confirmer or approver): not the caller, and proven to hold [what] by [check]. */
    private fun person(
        who: String,
        actor: String,
        label: String,
        entityRef: String,
        what: String,
        check: () -> Boolean,
    ): ElectionOutcome.Forbidden? =
        when {
            who == actor -> {
                ElectionOutcome.Forbidden("the $label must be a person other than the caller")
            }

            !holds(check) -> {
                ElectionOutcome.Forbidden(
                    "the $label does not hold $what for entity $entityRef or cannot be proven",
                )
            }

            else -> {
                null
            }
        }

    /** A change: a stored version exists and the new value or basis differs by equality, on the stored date or not. */
    private fun isChange(
        before: List<ElectionRecord>,
        value: String,
        basis: String?,
    ): Boolean = before.lastOrNull()?.let { it.value != value || it.basis != basis } ?: false

    private fun repeated(
        before: List<ElectionRecord>,
        value: String,
        effectiveFrom: LocalDate,
    ): ElectionOutcome.Done? =
        before
            .firstOrNull { it.effectiveFrom == effectiveFrom && it.value == value }
            ?.let { ElectionOutcome.Done(it, repeated = true) }

    private fun postingRefusal(entityRef: String): ElectionOutcome.Refused? =
        facts.claimsInputTaxOnPostingBasis(entityRef).takeIf { it != FactAnswer.TRUE }?.let {
            ElectionOutcome.Refused(
                "POSTING_DATE is refused for entity $entityRef: the fact claimsInputTaxOnPostingBasis is $it, " +
                    "not TRUE (INV-REQ-1283)",
            )
        }

    /** VP-25.OBSERVE_ROUTE (INV-REQ-1310): OBSERVE needs an acknowledgement route on the entity's connection. */
    private fun observeRefusal(entityRef: String): ElectionOutcome.Refused? =
        when (val found = routes.lookup(entityRef)) {
            RouteLookup.NoConnection -> {
                ElectionOutcome.Refused(
                    "OBSERVE is refused: entity $entityRef has no Access Point connection that lists it",
                )
            }

            is RouteLookup.NoRoute -> {
                ElectionOutcome.Refused(
                    "OBSERVE is refused: Access Point connection ${found.connectionRef} has no acknowledgement " +
                        "route elected (callback, polling or report import)",
                )
            }

            is RouteLookup.Elected -> {
                null
            }
        }

    private fun listedRefusal(
        source: String,
        basis: String,
        effectiveFrom: LocalDate,
    ): ElectionOutcome.Refused? {
        val listed = scopePacks.approvedOn(effectiveFrom)
        return when {
            listed == null -> {
                ElectionOutcome.Refused("no scope pack is active on $effectiveFrom, so no source is approved")
            }

            listed.none { it.sourceId == source && basis in it.bases } -> {
                ElectionOutcome.Refused(
                    "source $source with basis $basis is not on the approved list of the scope pack active on " +
                        effectiveFrom,
                )
            }

            else -> {
                null
            }
        }
    }

    @Suppress("LongParameterList") // one election with its actor
    private fun store(
        entityRef: String,
        groupRef: String,
        kind: ElectionKind,
        value: String,
        basis: String?,
        effectiveFrom: LocalDate,
        actor: String,
        reason: String,
    ): ElectionOutcome? =
        try {
            elections.write(
                entityRef,
                groupRef,
                kind,
                value,
                basis,
                effectiveFrom,
                actor,
                MANAGERS,
                reason,
            )
            null
        } catch (refused: ElectionWriteRefused) {
            if (refused.forbidden) {
                ElectionOutcome.Forbidden(refused.message.orEmpty())
            } else {
                ElectionOutcome.Refused(refused.message.orEmpty())
            }
        }

    /** EntityProfileChanged with the issuance mode entry in changedAttributes and node.profile (INV-Q-4993 B). */
    private fun publishIssuanceMode(
        tenant: TenantId,
        entityRef: String,
        before: List<ElectionRecord>,
        mode: IssuanceMode,
        effectiveFrom: LocalDate,
    ) {
        val entity = checkNotNull(structure.entity(entityRef))
        val previous = StatusTimeline.inForceOn(before, effectiveFrom)?.value
        val version = structure.nextStructureVersion()
        structure.touchEntity(entity.ref, version)
        val profile =
            (
                statuses.history(entity.id).map { ProfileEntry(it.attribute, it.value, it.effectiveFrom) } +
                    issuanceEntries(entity.ref)
            ).sortedBy { it.effectiveDate }
        publisher.publish(
            EntityProfileChange(
                tenant.value,
                entity.ref,
                listOf(ChangedAttribute(ISSUANCE_MODE_ATTRIBUTE, previous, mode.name)),
                effectiveFrom,
                entityNode(entity.copy(structureVersion = version), profile),
            ),
        )
    }

    private fun issuanceEntries(entityRef: String): List<ProfileEntry> =
        elections
            .history(entityRef, ElectionKind.ISSUANCE_MODE)
            .map { ProfileEntry(ISSUANCE_MODE_ATTRIBUTE, it.value, it.effectiveFrom) }

    private companion object {
        /** What the writer's setting-role check is shown for a caller proven to hold `entity-config.manage`. */
        val MANAGERS: Set<String> = setOf(ElectionRoles.ENTITY_ADMIN)
    }
}
