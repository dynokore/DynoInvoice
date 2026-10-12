package com.dynokore.platform.productruntime

/** The three replicas service-runtime keeps in every service (INV-REQ-1199, INV-ADR-284). */
enum class ReplicaKind {
    STRUCTURE,
    AUTHORITY,
    POLICY,
}

/**
 * One event feeding one replica: its CloudEvents type, its topic and, for structure and authority, the service the
 * replica falls back to (AD-26). The fallback is never on the steady-state path (INV-REQ-1199 b3).
 */
data class ReplicaFeed(
    val eventType: String,
    val topic: String,
    val replica: ReplicaKind,
    val fallbackService: String?,
)

/**
 * The product's configuration of service-runtime's replicas (INV-MOD-2745, AD-26): OrganisationStructureChanged and
 * EntityProfileChanged feed the structure replica with tenancy-svc as its fallback (INV-REQ-1200), AuthorityChanged
 * the authority replica with access-svc as its fallback (INV-REQ-1201), and PolicyChanged the policy replica, which
 * has no fallback (INV-REQ-1241 b2).
 */
object ReplicaFeeds {
    const val TENANCY_SVC = "tenancy-svc"
    const val ACCESS_SVC = "access-svc"

    val ALL: List<ReplicaFeed> =
        listOf(
            ReplicaFeed(
                ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED,
                ProductEventTypes.ORGANISATION_STRUCTURE_CHANGED_TOPIC,
                ReplicaKind.STRUCTURE,
                TENANCY_SVC,
            ),
            ReplicaFeed(
                ProductEventTypes.ENTITY_PROFILE_CHANGED,
                ProductEventTypes.ENTITY_PROFILE_CHANGED_TOPIC,
                ReplicaKind.STRUCTURE,
                TENANCY_SVC,
            ),
            ReplicaFeed(
                ProductEventTypes.AUTHORITY_CHANGED,
                ProductEventTypes.AUTHORITY_CHANGED_TOPIC,
                ReplicaKind.AUTHORITY,
                ACCESS_SVC,
            ),
            ReplicaFeed(
                PlatformEventCatalogue.POLICY_CHANGED.eventType,
                requireNotNull(PlatformEventCatalogue.POLICY_CHANGED.topic),
                ReplicaKind.POLICY,
                null,
            ),
        )

    /** The feeds of [replica], in this order. */
    fun of(replica: ReplicaKind): List<ReplicaFeed> = ALL.filter { it.replica == replica }

    /** The feed of [eventType], or null when no replica takes it. */
    fun forType(eventType: String): ReplicaFeed? = ALL.firstOrNull { it.eventType == eventType }
}
