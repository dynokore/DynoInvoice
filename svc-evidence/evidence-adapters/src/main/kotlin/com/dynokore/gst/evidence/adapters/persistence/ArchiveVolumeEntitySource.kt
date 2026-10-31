package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.jobs.JobDefinition
import com.dynokore.platform.serviceruntime.jobs.JobEntitySource
import com.dynokore.platform.serviceruntime.jobs.JobTarget
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The (tenant, entity) pairs the archive-volume job runs for (INV-Q-12685 A, INV-Q-12687 A): the key columns of the
 * SECURITY DEFINER function `evidence.archive_volume_entities()` and nothing else, read as the platform tenant the way
 * [com.dynokore.gst.evidence.adapters.jobs.VerificationEntitySource] reads its function. The reserved tenant-wide
 * entity cannot be a [JobTarget.Entity] (service-runtime refuses it), so a pair that names it is left out.
 */
class ArchiveVolumeEntitySource(
    private val scope: TenantScope,
    private val dsl: DSLContext,
) : JobEntitySource {
    private val mapper = JsonMapper.builder().build()

    override fun entities(job: JobDefinition): List<JobTarget.Entity> =
        scope.bindWorkload(TenantId.PLATFORM) {
            scope.transactions.inTransaction {
                dsl
                    .select(PAIR)
                    .fetch()
                    .map { mapper.readTree(it.value1().data()) }
                    .map {
                        EntityRef(it["entity_ref"].asString()) to
                            TenantId(UUID.fromString(it["tenant_id"].asString()))
                    }.filterNot { (entity, _) -> entity.isTenantWide }
                    .map { (entity, tenant) -> JobTarget.Entity(tenant, entity) }
            }
        }
}

/** One jsonb object `{tenant_id, entity_ref}` per row the set-returning function yields (no plain SQL: TPL-09). */
private val PAIR =
    DSL.function(
        DSL.name("to_jsonb"),
        SQLDataType.JSONB,
        DSL.function(DSL.name("evidence", "archive_volume_entities"), SQLDataType.OTHER),
    )
