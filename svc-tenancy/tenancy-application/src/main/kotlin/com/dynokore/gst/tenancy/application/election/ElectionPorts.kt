package com.dynokore.gst.tenancy.application.election

import com.dynokore.gst.tenancy.domain.Effective
import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.gst.tenancy.domain.FactAnswer
import java.time.Instant
import java.time.LocalDate

/**
 * One stored election of an entity (INV-REQ-323, INV-REQ-422, INV-REQ-505): the option key, for the rate source the
 * source id with its [basis], the date it takes effect and the person who recorded it.
 */
data class ElectionRecord(
    val value: String,
    val basis: String?,
    override val effectiveFrom: LocalDate,
    val setBy: String,
    val reason: String = "",
) : Effective

/**
 * A change of an election already recorded that waits for approval (INV-REQ-370, INV-REQ-422, INV-REQ-505; owner answer
 * INV-Q-10105): the requested value ([basis] for the rate source), the date it takes effect and who asked.
 */
data class PendingChange(
    val changeRef: String,
    val entityRef: String,
    val kind: ElectionKind,
    val value: String,
    val basis: String?,
    val effectiveFrom: LocalDate,
    val requestedBy: String,
    val requestedAt: Instant,
)

/** A change that was approved: the pending change with the person and time of the approval. */
data class ApprovedChange(
    val change: PendingChange,
    val approvedBy: String,
    val approvedAt: Instant,
)

/** A change that was closed without taking effect: withdrawn by its requester or declined by another person. */
data class ClosedChange(
    val change: PendingChange,
    val closedBy: String,
    val closedAt: Instant,
)

/** One page of the PENDING changes of an entity; [next] is the opaque token of the following page, else null. */
data class PendingChangePage(
    val items: List<PendingChange>,
    val next: String?,
)

/** The pending changes of the elections of the bound tenant (table `election_change`, forced RLS). */
interface ElectionChangeStore {
    /**
     * Stores a PENDING change of [kind] requested by [requestedBy]; null, and nothing stored, when the entity already
     * has a PENDING change for the same variation point (owner answer INV-Q-10117, option A).
     */
    @Suppress("LongParameterList") // one request with its requester
    fun request(
        entityRef: String,
        kind: ElectionKind,
        value: String,
        basis: String?,
        effectiveFrom: LocalDate,
        requestedBy: String,
    ): PendingChange?

    /** The change of this entity and tenant that is still PENDING, else null (unknown, other entity or decided). */
    fun pending(
        entityRef: String,
        changeRef: String,
    ): PendingChange?

    /** Sets PENDING to APPROVED; null when the change is no longer PENDING (already decided). */
    fun approve(
        entityRef: String,
        changeRef: String,
        approvedBy: String,
    ): ApprovedChange?

    /** Sets PENDING to WITHDRAWN; null when the change is no longer PENDING (already decided). */
    fun withdraw(
        entityRef: String,
        changeRef: String,
        closedBy: String,
    ): ClosedChange?

    /** Sets PENDING to DECLINED; null when the change is no longer PENDING (already decided). */
    fun decline(
        entityRef: String,
        changeRef: String,
        closedBy: String,
    ): ClosedChange?

    /**
     * The PENDING changes of the entity, ordered by `requestedAt` then `changeRef`, at most [limit] items after the
     * position [after] (the [PendingChangePage.next] token of the previous page, null for the first page).
     *
     * @throws IllegalArgumentException when [after] is not a token this store issued.
     */
    fun list(
        entityRef: String,
        limit: Int,
        after: String?,
    ): PendingChangePage
}

/**
 * The audit entries of the two-step flow (INV-REQ-370 behavior 6, INV-REQ-422 behavior 4), written in the caller's
 * transaction so each commits with the change it records.
 */
interface ElectionAudit {
    /** "requested": names the requester, the variation point and the requested value. */
    fun requested(change: PendingChange)

    /** "approved": names the approver and the requester. */
    fun approved(approval: ApprovedChange)

    /** "withdrawn": names the user ID of the requester who withdrew and the change. */
    fun withdrawn(closure: ClosedChange)

    /** "declined": names the user ID of the decliner and of the requester. */
    fun declined(closure: ClosedChange)

    /** "exempt-purchase recorded": names the acting user ID and the codes of the INV-REQ-369 election. */
    fun exemptPurchaseRecorded(record: ExemptPurchaseAuditRecord)
}

/** What the audit entry of a recorded exempt-purchase election holds; [recordedBy] is the user ID, never a role. */
data class ExemptPurchaseAuditRecord(
    val entityRef: String,
    val taxCodes: List<String>,
    val recordedBy: String,
    val recordedAt: Instant,
)

/** The verified caller of the operation in hand (the end-user token's `sub`, API-24), never a request field. */
fun interface CallerSource {
    fun actor(): String
}

/**
 * Whether [person] holds [action] (a permission key of the catalogue) for the entity in the caller's tenant (ruling
 * INV-Q-10025). An unknown person, a revoked or expired grant, a grant on another entity and a text that is not
 * exactly a person's id all answer false. An exception is treated by the caller as false (fail closed).
 */
fun interface ActionAuthority {
    fun holds(
        person: String,
        entityRef: String,
        action: String,
    ): Boolean
}

/**
 * The entity-level facts of the onboarding questionnaire the elections depend on (owner answer INV-Q-9696 B). The
 * default binding answers NOT_RECORDED for every fact; INV-T-4933 replaces it with the stored answers. Each method
 * is asked for an entity of the caller's tenant only.
 */
interface EntityFactSource {
    /** Whether the entity records purchases: decides whether the apportionment mode must be elected. */
    fun recordsPurchases(entityRef: String): FactAnswer

    /** Whether the entity has foreign-currency documents: decides whether the exchange-rate source must be elected. */
    fun hasForeignCurrencyDocuments(entityRef: String): FactAnswer

    /** Whether the entity claims input tax on the posting basis: decides whether POSTING_DATE may be elected. */
    fun claimsInputTaxOnPostingBasis(entityRef: String): FactAnswer
}

/** The elections as config-resolver stores them (VP-22, VP-23, VP-24, VP-25), in the caller's transaction. */
interface ElectionPolicyStore {
    /** Every election of [kind] of the entity, oldest effective date first; one entry per date (the last recorded). */
    fun history(
        entityRef: String,
        kind: ElectionKind,
    ): List<ElectionRecord>

    /**
     * Stores the election with the caller as actor; the store decides by permission, never by a role (INV-Q-10027).
     *
     * @throws ElectionWriteRefused when the store refuses the value; nothing is stored.
     */
    @Suppress("LongParameterList") // one election with its actor
    fun write(
        entityRef: String,
        groupRef: String,
        kind: ElectionKind,
        value: String,
        basis: String?,
        effectiveFrom: LocalDate,
        actor: String,
        reason: String,
    )
}

/** The store refused the election: [forbidden] when the caller lacks the setting role, else the value is refused. */
class ElectionWriteRefused(
    val forbidden: Boolean,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** What the route replica says about the Access Point connection of an entity (INV-REQ-1310, VP-25.OBSERVE_ROUTE). */
sealed interface RouteLookup {
    /** No row of the entity's tenant lists the entity: it has no Access Point connection. */
    data object NoConnection : RouteLookup

    /** The row that lists the entity says no route is elected. */
    data class NoRoute(
        val connectionRef: String,
    ) : RouteLookup

    data class Elected(
        val connectionRef: String,
        val route: String,
    ) : RouteLookup
}

/** One AccessPointRouteChanged event as the replica takes it. */
data class RouteUpdate(
    val connectionRef: String,
    val entityRefs: List<String>,
    val route: String?,
    val version: Long,
)

/** The route replica of the bound tenant, one row per Access Point connection. */
interface AccessPointRouteStore {
    /** Replaces the row when [RouteUpdate.version] is above the stored one; true when it did, false when ignored. */
    fun apply(update: RouteUpdate): Boolean

    fun lookup(entityRef: String): RouteLookup
}

/** One approved exchange-rate source of a scope pack and the bases it is approved for (INV-REQ-504). */
data class ApprovedSource(
    val sourceId: String,
    val bases: Set<String>,
)

/** The replica of the scope pack's approved exchange-rate sources (ruling INV-Q-9698), of the bound tenant. */
interface ScopePackStore {
    /** Stores a staged scope pack version; false when the (pack, version) is already held. */
    fun stage(
        packIdentifier: String,
        version: String,
        sources: List<ApprovedSource>,
        recordedAt: Instant,
    ): Boolean

    /** Records an activation; false when it is not above the pack's latest stored activation date. */
    fun activate(
        packIdentifier: String,
        version: String,
        effectiveFrom: LocalDate,
        recordedAt: Instant,
    ): Boolean

    /** The sources of the scope pack versions active on [on], or null when none is. */
    fun approvedOn(on: LocalDate): List<ApprovedSource>?
}
