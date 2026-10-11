package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.tenancykernel.TenantId

/**
 * What one run, pause or command covers (INV-Q-2985, INV-Q-3032): one entity's share of an ENTITY-scoped
 * job, or the whole job (every run of a PLATFORM-scoped job, a whole-job run-now, a job-wide pause).
 * The whole job's rows and events are written under the reserved platform tenant (INV-Q-2927 option D).
 */
sealed interface JobTarget {
    val tenant: TenantId
    val entity: EntityRef

    /** The whole job: platform tenant and platform entity, absent on the wire. */
    data object Whole : JobTarget {
        override val tenant: TenantId get() = TenantId.PLATFORM
        override val entity: EntityRef get() = EntityRef.PLATFORM
    }

    /** One entity's share; [tenant] is the entity's tenant, never the platform tenant. */
    data class Entity(override val tenant: TenantId, override val entity: EntityRef) : JobTarget {
        init {
            require(!tenant.isPlatform && !entity.isPlatform && !entity.isTenantWide) {
                "an entity share needs a real tenant and entity"
            }
            require(entity.value.length <= MAX_ENTITY_REF) { "entity reference longer than $MAX_ENTITY_REF" }
        }
    }

    companion object {
        /** `JobEntityRef.maxLength` of the service-runtime operation contract. */
        const val MAX_ENTITY_REF = 40
    }
}

/** SCHEDULED for a firing, MANUAL for a run-now; part of the run's key (INV-Q-3039). */
enum class JobTrigger { SCHEDULED, MANUAL }

/** A run's status (`JobRunState.status`, `JobRunRecorded.outcome.status`). */
enum class JobRunStatus { IN_PROGRESS, SUCCEEDED, FAILED }
