package com.dynokore.platform.serviceruntime.replica

import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.LocalDate

enum class StructureNodeKind { TENANT, GROUP, ENTITY, BUSINESS_UNIT }

enum class StructureChange { CREATED, MOVED, RETIRED }

data class StructureKey(val tenant: TenantId, val nodeRef: String)

/** One node of a tenant's organisation structure as this service holds it. */
data class StructureNode(
    val kind: StructureNodeKind,
    val parentRef: String?,
    val retired: Boolean = false,
    val attributes: Map<String, String> = emptyMap(),
    val profileEffectiveDate: LocalDate? = null,
)

/** The two events that feed the structure replica. */
sealed interface StructureEvent {
    val version: Long
}

/**
 * The platform event `OrganisationStructureChanged` (a tenant, group, entity or business unit
 * created, moved or retired).
 */
data class OrganisationStructureChanged(
    val tenant: TenantId,
    val nodeRef: String,
    val kind: StructureNodeKind,
    val parentRef: String?,
    val change: StructureChange,
    override val version: Long,
) : StructureEvent

/** The platform event `EntityProfileChanged` (an entity's changed attributes and their effective date). */
data class EntityProfileChanged(
    val tenant: TenantId,
    val entityRef: String,
    val changedAttributes: Map<String, String>,
    val effectiveDate: LocalDate,
    override val version: Long,
) : StructureEvent

/** The synchronous fallback to `tenancy-svc` for a miss (INV-REQ-1200 behaviour #2). */
fun interface StructureFallback : ReplicaFallback<StructureKey, StructureNode>

/**
 * The structure replica (INV-REQ-1200): fed by `OrganisationStructureChanged` and
 * `EntityProfileChanged`, fills a miss from `tenancy-svc`, and keeps serving its
 * last state when `tenancy-svc` is down. No lag bound: structure is served however
 * old it is, because a stale tree beats no tree.
 */
class StructureReplica(
    clock: Clock,
    fallback: ReplicaFallback<StructureKey, StructureNode>? = null,
) : ReplicaFeedSink<StructureEvent> {
    val replica = EventFedReplica<StructureKey, StructureNode>("structure", clock, lagBound = null, fallback = fallback)

    fun node(
        tenant: TenantId,
        nodeRef: String,
        deadline: Deadline,
    ): Lookup<StructureNode> = replica.get(StructureKey(tenant, nodeRef), deadline)

    override fun accept(event: StructureEvent) {
        when (event) {
            is OrganisationStructureChanged -> apply(event)
            is EntityProfileChanged -> apply(event)
        }
    }

    fun apply(event: OrganisationStructureChanged) {
        replica.update(StructureKey(event.tenant, event.nodeRef), event.version) { old ->
            val base = old ?: StructureNode(event.kind, event.parentRef)
            base.copy(
                kind = event.kind,
                parentRef = event.parentRef,
                retired = event.change == StructureChange.RETIRED,
            )
        }
    }

    /** A profile change to an entity not yet held creates it as an ENTITY with no parent known. */
    fun apply(event: EntityProfileChanged) {
        replica.update(StructureKey(event.tenant, event.entityRef), event.version) { old ->
            val base = old ?: StructureNode(StructureNodeKind.ENTITY, parentRef = null)
            base.copy(
                attributes = base.attributes + event.changedAttributes,
                profileEffectiveDate = event.effectiveDate,
            )
        }
    }

    override fun observeHead(version: Long) = replica.tracker.observeHead(version)

    override fun onDisconnected() = replica.tracker.onDisconnected()

    fun lag(): Lag = replica.lag()
}
