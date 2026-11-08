package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ARCHIVE_VOLUME
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITLEMENT_LIMIT
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.PLAN_LIMIT_RAISED
import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.USAGE_COUNTER
import com.dynokore.gst.tenancy.application.metering.Entitlement
import com.dynokore.gst.tenancy.application.metering.EntityCounters
import com.dynokore.gst.tenancy.application.metering.LimitKind
import com.dynokore.gst.tenancy.application.metering.MeteringStore
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** The metering tables of the bound tenant (jOOQ only, CS-18); forced RLS hides every other tenant's rows. */
@Suppress("TooManyFunctions") // one function per metering store operation
class JooqMeteringStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : MeteringStore {
    private val tenant get() = tenants.current().value

    override fun count(
        entityRef: String,
        period: String,
        resubmission: Boolean,
    ): Boolean {
        if (!dsl.fetchExists(ENTITY, ENTITY.TENANT_ID.eq(tenant).and(ENTITY.REF.eq(entityRef)))) return false
        val documents = if (resubmission) 0 else 1
        val resubmissions = if (resubmission) 1 else 0
        dsl
            .insertInto(USAGE_COUNTER)
            .set(USAGE_COUNTER.TENANT_ID, tenant)
            .set(USAGE_COUNTER.ENTITY_REF, entityRef)
            .set(USAGE_COUNTER.PERIOD, period)
            .set(USAGE_COUNTER.DOCUMENTS, documents)
            .set(USAGE_COUNTER.RESUBMISSIONS, resubmissions)
            .onConflict(USAGE_COUNTER.TENANT_ID, USAGE_COUNTER.ENTITY_REF, USAGE_COUNTER.PERIOD)
            .doUpdate()
            .set(USAGE_COUNTER.DOCUMENTS, USAGE_COUNTER.DOCUMENTS.plus(documents))
            .set(USAGE_COUNTER.RESUBMISSIONS, USAGE_COUNTER.RESUBMISSIONS.plus(resubmissions))
            .execute()
        return true
    }

    override fun documentsIn(period: String): Int = sum(USAGE_COUNTER.DOCUMENTS, period)

    override fun resubmissionsIn(period: String): Int = sum(USAGE_COUNTER.RESUBMISSIONS, period)

    private fun sum(
        column: org.jooq.TableField<*, Int?>,
        period: String,
    ): Int =
        dsl
            .select(DSL.coalesce(DSL.sum(column), java.math.BigDecimal.ZERO))
            .from(USAGE_COUNTER)
            .where(USAGE_COUNTER.TENANT_ID.eq(tenant).and(USAGE_COUNTER.PERIOD.eq(period)))
            .fetchSingle()
            .value1()
            .toInt()

    override fun activeEntities(): Int =
        dsl.fetchCount(
            ENTITY,
            ENTITY.TENANT_ID
                .eq(tenant)
                .and(ENTITY.LIFECYCLE_STATE.ne("DEREGISTERED"))
                .and(ENTITY.RETIRED.isFalse),
        )

    override fun entities(
        period: String,
        only: String?,
    ): List<EntityCounters> {
        val counters =
            dsl
                .selectFrom(USAGE_COUNTER)
                .where(USAGE_COUNTER.TENANT_ID.eq(tenant).and(USAGE_COUNTER.PERIOD.eq(period)))
                .fetch()
                .associate {
                    checkNotNull(it[USAGE_COUNTER.ENTITY_REF]) to
                        (checkNotNull(it[USAGE_COUNTER.DOCUMENTS]) to checkNotNull(it[USAGE_COUNTER.RESUBMISSIONS]))
                }
        val volumes =
            dsl
                .selectFrom(ARCHIVE_VOLUME)
                .where(ARCHIVE_VOLUME.TENANT_ID.eq(tenant).and(ARCHIVE_VOLUME.ENTITY_REF.ne(TENANT_WIDE)))
                .fetch()
                .associate { checkNotNull(it[ARCHIVE_VOLUME.ENTITY_REF]) to checkNotNull(it[ARCHIVE_VOLUME.BYTES]) }
        return (counters.keys + volumes.keys)
            .filter { only == null || it == only }
            .sorted()
            .map { ref ->
                EntityCounters(ref, counters[ref]?.first ?: 0, counters[ref]?.second ?: 0, volumes[ref])
            }
    }

    override fun keepVolume(
        entityRef: String?,
        bytes: Long,
        measuredAt: Instant,
    ) {
        val at = OffsetDateTime.ofInstant(measuredAt, ZoneOffset.UTC)
        dsl
            .insertInto(ARCHIVE_VOLUME)
            .set(ARCHIVE_VOLUME.TENANT_ID, tenant)
            .set(ARCHIVE_VOLUME.ENTITY_REF, entityRef ?: TENANT_WIDE)
            .set(ARCHIVE_VOLUME.BYTES, bytes)
            .set(ARCHIVE_VOLUME.MEASURED_AT, at)
            .onConflict(ARCHIVE_VOLUME.TENANT_ID, ARCHIVE_VOLUME.ENTITY_REF)
            .doUpdate()
            .set(ARCHIVE_VOLUME.BYTES, bytes)
            .set(ARCHIVE_VOLUME.MEASURED_AT, at)
            .where(ARCHIVE_VOLUME.MEASURED_AT.lt(at))
            .execute()
    }

    override fun totalVolume(): Long? =
        dsl
            .select(DSL.sum(ARCHIVE_VOLUME.BYTES), DSL.count())
            .from(ARCHIVE_VOLUME)
            .where(ARCHIVE_VOLUME.TENANT_ID.eq(tenant))
            .fetchSingle()
            .let { if (it.value2() == 0) null else it.value1().toLong() }

    override fun putEntitlement(entitlement: Entitlement) {
        put(LimitKind.DOCUMENTS_PER_MONTH, entitlement.planVersion, entitlement.maxDocumentsPerMonth)
        put(LimitKind.ENTITIES, entitlement.planVersion, entitlement.maxEntities)
    }

    private fun put(
        kind: LimitKind,
        planVersion: String,
        value: Int,
    ) {
        dsl
            .insertInto(ENTITLEMENT_LIMIT)
            .set(ENTITLEMENT_LIMIT.TENANT_ID, tenant)
            .set(ENTITLEMENT_LIMIT.LIMIT_KIND, kind.name)
            .set(ENTITLEMENT_LIMIT.PLAN_VERSION, planVersion)
            .set(ENTITLEMENT_LIMIT.LIMIT_VALUE, value)
            .onConflict(ENTITLEMENT_LIMIT.TENANT_ID, ENTITLEMENT_LIMIT.LIMIT_KIND)
            .doUpdate()
            .set(ENTITLEMENT_LIMIT.PLAN_VERSION, planVersion)
            .set(ENTITLEMENT_LIMIT.LIMIT_VALUE, value)
            .execute()
    }

    override fun entitlement(kind: LimitKind): Pair<String, Int>? =
        dsl
            .selectFrom(ENTITLEMENT_LIMIT)
            .where(ENTITLEMENT_LIMIT.TENANT_ID.eq(tenant).and(ENTITLEMENT_LIMIT.LIMIT_KIND.eq(kind.name)))
            .fetchOne()
            ?.let {
                checkNotNull(
                    it[ENTITLEMENT_LIMIT.PLAN_VERSION],
                ) to checkNotNull(it[ENTITLEMENT_LIMIT.LIMIT_VALUE])
            }

    override fun markRaised(
        kind: LimitKind,
        period: String,
    ): Boolean =
        dsl
            .insertInto(PLAN_LIMIT_RAISED)
            .set(PLAN_LIMIT_RAISED.TENANT_ID, tenant)
            .set(PLAN_LIMIT_RAISED.LIMIT_KIND, kind.name)
            .set(PLAN_LIMIT_RAISED.PERIOD, period)
            .set(PLAN_LIMIT_RAISED.RAISED_AT, OffsetDateTime.now(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() == 1

    private companion object {
        /** The archive_volume row of the tenant-wide value: the reserved value of the platform's tenant-wide entity. */
        val TENANT_WIDE: String = EntityRef.TENANT_WIDE.value
    }
}
