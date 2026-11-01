package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.AcknowledgementRoute
import com.dynokore.gst.transport.domain.ConnectionRouteState
import com.dynokore.gst.transport.domain.EntityNode
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.IssuanceReplicaRow
import com.dynokore.gst.transport.domain.ObserveGuard
import com.dynokore.gst.transport.domain.ObserveRefusal
import com.dynokore.gst.transport.domain.RouteChange
import com.dynokore.gst.transport.domain.TenantRef
import java.time.Clock
import java.time.LocalDate

/** The connection store: a connection is the `adapter_id` record of the bound tenant; [load] is null when none. */
interface AccessPointConnectionStore {
    fun load(
        tenant: TenantRef,
        connectionRef: String,
    ): ConnectionRouteState?

    fun save(
        tenant: TenantRef,
        connectionRef: String,
        state: ConnectionRouteState,
    )
}

/** Writes `AccessPointRouteChanged`; called in the same unit of work as every store of a route change. */
interface RouteChangePublisher {
    fun publish(
        tenant: TenantRef,
        connectionRef: String,
        route: AcknowledgementRoute?,
        entityRefs: List<EntityRef>,
        version: Long,
    )
}

/** The unit of work a store and its event share. */
interface RouteUnitOfWork {
    fun <T> run(block: () -> T): T
}

/** The issuanceMode replica, by entity. */
interface IssuanceReplica {
    fun find(entity: EntityRef): IssuanceReplicaRow?

    fun replace(
        entity: EntityRef,
        row: IssuanceReplicaRow,
    )
}

/** The connection ref names no record of the tenant: not found (the endpoint answers 404 SRT-005). */
class ConnectionNotFound(
    connectionRef: String,
) : NoSuchElementException("connection $connectionRef not found")

/** Clearing (SUB-023) or removal (SUB-019) is refused by the Observe guard. */
class ObserveGuardRefused(
    val code: String,
    val refusal: ObserveRefusal,
) : IllegalStateException("$code: ${refusal.message}")

/** Result of a use case: whether the stored state changed. */
enum class RouteOutcome { CHANGED, NO_OP }

/** The one publishing function: store and event of a route or entity-refs change share one unit of work. */
class RouteChangeRecorder(
    private val store: AccessPointConnectionStore,
    private val publisher: RouteChangePublisher,
    private val unit: RouteUnitOfWork,
) {
    fun load(
        tenant: TenantRef,
        connectionRef: String,
    ): ConnectionRouteState = store.load(tenant, connectionRef) ?: throw ConnectionNotFound(connectionRef)

    /** Stores a change and publishes once; a no-op stores nothing. INV-T-4983 calls this for entity refs. */
    fun record(
        tenant: TenantRef,
        connectionRef: String,
        change: RouteChange,
    ): RouteOutcome {
        if (change !is RouteChange.Changed) return RouteOutcome.NO_OP
        val s = change.state
        unit.run {
            store.save(tenant, connectionRef, s)
            publisher.publish(tenant, connectionRef, s.route, s.entityRefs, s.version)
        }
        return RouteOutcome.CHANGED
    }
}

/** Elects the acknowledgement route of a connection; a fourth value is a validation error before anything is read. */
class ElectAcknowledgementRoute(
    private val recorder: RouteChangeRecorder,
) {
    fun elect(
        tenant: TenantRef,
        connectionRef: String,
        route: String?,
    ): RouteOutcome {
        val parsed = AcknowledgementRoute.parse(route)
        return recorder.record(tenant, connectionRef, recorder.load(tenant, connectionRef).elect(parsed))
    }
}

/** Clears the route unless an entity of the connection is, or may be, in Observe mode (SUB-023). */
class ClearAcknowledgementRoute(
    private val recorder: RouteChangeRecorder,
    private val replica: IssuanceReplica,
    private val clock: Clock,
) {
    fun clear(
        tenant: TenantRef,
        connectionRef: String,
    ): RouteOutcome {
        val state = recorder.load(tenant, connectionRef)
        val change = state.clear()
        if (change is RouteChange.NoOp) return RouteOutcome.NO_OP
        ObserveGuard.refusal(connectionRef, state.entityRefs, replica::find, LocalDate.now(clock))?.let {
            throw ObserveGuardRefused("SUB-023", it)
        }
        return recorder.record(tenant, connectionRef, change)
    }
}

/** The Observe check for the removal of an entity from a connection (SUB-019), called by INV-T-4983. */
class EntityRemovalGuard(
    private val replica: IssuanceReplica,
    private val clock: Clock,
) {
    fun check(
        connectionRef: String,
        entity: EntityRef,
    ) {
        ObserveGuard.refusal(connectionRef, listOf(entity), replica::find, LocalDate.now(clock))?.let {
            throw ObserveGuardRefused("SUB-019", it)
        }
    }
}

/** Applies an EntityProfileChanged event to the replica row of its entity. */
class ApplyEntityProfileChanged(
    private val replica: IssuanceReplica,
) {
    fun apply(
        tenant: TenantRef,
        entity: EntityRef,
        node: EntityNode?,
    ) {
        val stored = replica.find(entity)
        val next = IssuanceReplicaRow.apply(stored, tenant, node)
        if (next != null && next != stored) replica.replace(entity, next)
    }
}
