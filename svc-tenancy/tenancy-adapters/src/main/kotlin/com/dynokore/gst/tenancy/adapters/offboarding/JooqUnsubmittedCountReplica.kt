package com.dynokore.gst.tenancy.adapters.offboarding

import com.dynokore.gst.tenancy.adapters.events.EntityUnsubmittedCount
import com.dynokore.gst.tenancy.adapters.events.UnsubmittedCountMeasurement
import com.dynokore.gst.tenancy.adapters.events.UnsubmittedCountReplica
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.UNSUBMITTED_COUNT_REPLICA
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** A stored row of the replica: the measurement and the receipt time of the consumer. */
data class StoredUnsubmittedCount(
    val measurement: UnsubmittedCountMeasurement,
    val receivedAt: Instant,
)

/**
 * The replica of the UnsubmittedDocumentCountMeasured event (INV-AC-23029) on the tenant-bound [DSLContext]: forced
 * row-level security hides every other tenant's row, and `tenancy_runtime` cannot delete.
 */
class JooqUnsubmittedCountReplica(
    private val dsl: DSLContext,
) : UnsubmittedCountReplica {
    private val json = JsonMapper.builder().build()

    /** One statement: insert, or update only when the stored `measured_at` is strictly smaller. */
    override fun keep(
        tenant: TenantId,
        entity: EntityRef,
        measurement: UnsubmittedCountMeasurement,
        receivedAt: Instant,
    ): Boolean {
        val t = UNSUBMITTED_COUNT_REPLICA
        val measuredAt = utc(measurement.measuredAt)
        val received = utc(receivedAt)
        val entities = DSL.`val`(entitiesJson(measurement.entities)).cast(SQLDataType.JSONB)
        return dsl
            .insertInto(t)
            .set(t.TENANT_ID, tenant.value)
            .set(t.ENTITY_REF, entity.value)
            .set(t.MEASURED_AT, measuredAt)
            .set(t.RECEIVED_AT, received)
            .set(t.UNSUBMITTED_TOTAL, measurement.total)
            .set(t.ENTITIES, entities)
            .onConflict(t.TENANT_ID, t.ENTITY_REF)
            .doUpdate()
            .set(t.MEASURED_AT, measuredAt)
            .set(t.RECEIVED_AT, received)
            .set(t.UNSUBMITTED_TOTAL, measurement.total)
            .set(t.ENTITIES, entities)
            .where(t.MEASURED_AT.lt(measuredAt))
            .execute() == 1
    }

    /** The tenant-wide row of [tenant], or null; another tenant's row is invisible to the bound session. */
    fun read(tenant: TenantId): StoredUnsubmittedCount? {
        val t = UNSUBMITTED_COUNT_REPLICA
        val row =
            dsl
                .selectFrom(t)
                .where(t.TENANT_ID.eq(tenant.value).and(t.ENTITY_REF.eq(EntityRef.TENANT_WIDE.value)))
                .fetchOne() ?: return null
        val items =
            json.readTree(checkNotNull(row.entities).data()).values().map {
                EntityUnsubmittedCount(it["entityRef"].asString(), it["unsubmittedDocumentCount"].asInt())
            }
        return StoredUnsubmittedCount(
            UnsubmittedCountMeasurement(
                checkNotNull(row.measuredAt).toInstant(),
                checkNotNull(row.unsubmittedTotal),
                items,
            ),
            checkNotNull(row.receivedAt).toInstant(),
        )
    }

    private fun entitiesJson(entities: List<EntityUnsubmittedCount>): String {
        val array = json.createArrayNode()
        entities.forEach { array.addObject().put("entityRef", it.entityRef).put("unsubmittedDocumentCount", it.count) }
        return array.toString()
    }

    private fun utc(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
}
