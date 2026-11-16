package com.dynokore.gst.access.application.alert

import com.dynokore.gst.access.domain.grant.EntityRef
import java.time.Clock
import java.time.Instant

/**
 * The grants of role `tax.approver`, status ACTIVE, whose validity window contains `at` and whose scope covers
 * the entity, in the bound tenant.
 */
fun interface ActiveApprovers {
    fun countFor(
        entity: EntityRef,
        at: Instant,
    ): Int
}

/** True when any grant of role `tax.approver`, of any status, covers the entity. */
fun interface HeldApprover {
    fun everHeld(entity: EntityRef): Boolean
}

/** The open ACC-001 state per entity; `tryOpen` is true only for the call that creates the open state. */
interface OrphanAlertState {
    fun tryOpen(entity: EntityRef): Boolean

    fun close(entity: EntityRef)

    fun openEntities(): Set<EntityRef>
}

/** Called inside the transaction of a write that touched a `tax.approver` grant (ACC-001, INV-REQ-895). */
interface OrphanedApproverCheck {
    fun afterChange(entities: Set<EntityRef>)

    companion object {
        /** Does nothing; the default of the constructor parameters so no existing caller changes. */
        val NotWired: OrphanedApproverCheck =
            object : OrphanedApproverCheck {
                override fun afterChange(entities: Set<EntityRef>) = Unit
            }
    }
}

/**
 * ACC-001 for an entity left without an active `tax.approver`: raised once per entity (the open state is the
 * idempotence key), closed when an approver is active again. A failing port propagates, so the surrounding write rolls
 * back (fail closed). Role names are data of the condition, not an authorisation check.
 */
class OrphanedApprovers(
    private val active: ActiveApprovers,
    private val held: HeldApprover,
    private val state: OrphanAlertState,
    private val alerts: AccessAlerts,
    private val clock: Clock,
) : OrphanedApproverCheck {
    override fun afterChange(entities: Set<EntityRef>) {
        entities.forEach { entity ->
            if (active.countFor(entity, clock.instant()) > 0) {
                state.close(entity)
            } else if (held.everHeld(entity) && state.tryOpen(entity)) {
                alerts.raise(CODE, "entity:${entity.value}")
            }
        }
    }

    /** The level-triggered pass of the scheduled scan: [entities] are those the scan found without an approver. */
    fun settle(entities: Set<EntityRef>) {
        afterChange(entities)
        state.openEntities().forEach { entity ->
            if (entity !in entities || active.countFor(entity, clock.instant()) > 0) state.close(entity)
        }
    }

    private companion object {
        const val CODE = "ACC-001"
    }
}
