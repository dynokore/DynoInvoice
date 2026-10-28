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
     * Stores the election with the caller as actor and [roles] as the roles the caller is proven to hold.
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
        roles: Set<String>,
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
