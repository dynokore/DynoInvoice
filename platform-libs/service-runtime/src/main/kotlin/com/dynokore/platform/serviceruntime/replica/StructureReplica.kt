package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.LocalDate

/** A node's level (INV-REQ-850). [UNKNOWN] is any wire value this version does not recognise. */
enum class StructureNodeKind {
    TENANT,
    GROUP,
    ENTITY,
    BUSINESS_UNIT,
    UNKNOWN,
    ;

    companion object {
        fun of(wire: String): StructureNodeKind = entries.firstOrNull { it != UNKNOWN && it.name == wire } ?: UNKNOWN
    }
}

/**
 * `OrganisationStructureChanged`'s changeType (INV-Q-3337). [UNKNOWN] is any wire value this version does
 * not recognise; whatever the value, the event's full-state node is applied.
 */
enum class StructureChange {
    CREATED,
    MOVED,
    RETIRED,
    UPDATED,
    UNKNOWN,
    ;

    companion object {
        fun of(wire: String): StructureChange = entries.firstOrNull { it != UNKNOWN && it.name == wire } ?: UNKNOWN
    }
}

data class StructureKey(
    val tenant: TenantId,
    val nodeRef: String,
)

/** One profile attribute value and the date it takes effect (INV-REQ-323, INV-REQ-376, INV-REQ-852). */
data class ProfileEntry(
    val attribute: String,
    val value: String,
    val effectiveDate: LocalDate,
)

/**
 * One node of a tenant's organisation structure: the full node state that both structure events and a
 * `getEntityStructure` fill carry (INV-Q-3324 (a)), replaced whole and versioned by [structureVersion].
 */
data class StructureNode(
    val kind: StructureNodeKind,
    val parentRef: String?,
    val retired: Boolean,
    val name: String,
    val code: String?,
    val profile: List<ProfileEntry>,
    val structureVersion: Long,
) {
    /**
     * The value of [attribute] in force on [onDate]: the entry with the latest effective date not after
     * it. A future-dated entry is never served before its date; null when none is in force.
     */
    fun attribute(
        attribute: String,
        onDate: LocalDate,
    ): String? =
        profile
            .filter { it.attribute == attribute && !it.effectiveDate.isAfter(onDate) }
            .maxByOrNull { it.effectiveDate }
            ?.value

    /** Every attribute in force on [onDate]. */
    fun profileOn(onDate: LocalDate): Map<String, String> {
        val names = profile.map { it.attribute }.distinct()
        return names.mapNotNull { name -> attribute(name, onDate)?.let { name to it } }.toMap()
    }
}

/** The two events that feed the structure replica, each carrying the node's full state after the change. */
sealed interface StructureEvent {
    val tenant: TenantId
    val nodeRef: String
    val node: StructureNode
}

/** The platform event `OrganisationStructureChanged` (a node created, moved, retired or updated). */
data class OrganisationStructureChanged(
    override val tenant: TenantId,
    override val nodeRef: String,
    val change: StructureChange,
    override val node: StructureNode,
) : StructureEvent

/** The platform event `EntityProfileChanged` (an entity's profile changed). */
data class EntityProfileChanged(
    override val tenant: TenantId,
    val entityRef: String,
    override val node: StructureNode,
) : StructureEvent {
    override val nodeRef: String get() = entityRef
}

/** The synchronous `getEntityStructure` fallback to `tenancy-svc` for a miss (INV-REQ-1200 behaviour #2). */
fun interface StructureFallback : ReplicaFallback<StructureKey, StructureNode>

/**
 * The structure replica (INV-REQ-1200): fed by `OrganisationStructureChanged` and
 * `EntityProfileChanged`, fills a miss from `tenancy-svc`, and keeps serving its
 * last state when `tenancy-svc` is down. No lag bound: structure is served however
 * old it is, because a stale tree beats no tree.
 *
 * Every event and every fill replaces the node whole, at its structureVersion (INV-Q-3324): one
 * tenancy-svc sequence across both topics and the fill, so the order between the two topics does not
 * matter, and an older event or fill never overwrites a newer node (INV-REQ-1221). The changeType is
 * not read: an UPDATED or unknown value applies the full node like any other (INV-Q-3337).
 */
class StructureReplica(
    clock: Clock,
    fallback: ReplicaFallback<StructureKey, StructureNode>? = null,
) : ReplicaFeedSink<StructureEvent> {
    @Volatile
    private var synced = false

    val replica =
        EventFedReplica<StructureKey, StructureNode>(
            "structure",
            clock,
            lagBound = null,
            fallback = fallback,
            versionOf = StructureNode::structureVersion,
        )

    fun node(
        tenant: TenantId,
        nodeRef: String,
        deadline: Deadline,
    ): Lookup<StructureNode> = replica.get(StructureKey(tenant, nodeRef), deadline)

    override fun accept(event: StructureEvent) {
        val node = event.node
        replica.apply(ReplicaChange.Upsert(StructureKey(event.tenant, event.nodeRef), node, node.structureVersion))
    }

    override fun observeHead(version: Long) = replica.tracker.observeHead(version)

    override fun onSnapshotComplete(version: Long) {
        replica.tracker.onSnapshotComplete(version)
        synced = true
    }

    override fun onDisconnected() {
        replica.tracker.onDisconnected()
        synced = false
    }

    /**
     * True exactly after [onSnapshotComplete] was called and false after [onDisconnected]. It changes nothing in what
     * the replica serves: with no lag bound it keeps answering from what it holds either way (INV-AC-9089 reads it).
     */
    fun isSynced(): Boolean = synced

    fun lag(): Lag = replica.lag()
}
