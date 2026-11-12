package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.FilingOverride
import com.dynokore.gst.submission.application.gate.NewOverride
import com.dynokore.gst.submission.application.gate.OverrideStore
import com.dynokore.gst.submission.application.gate.StoredOverride
import com.dynokore.gst.submission.domain.gate.OverrideStatus
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.Record
import java.util.UUID

/**
 * [OverrideStore] over `submission.filing_override` (INV-REQ-72). Every statement runs in the caller's open tenant
 * transaction and filters by the bound tenant; [release] is one conditional update from PENDING.
 */
class JooqOverrideStore(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : OverrideStore {
    private val t = FilingOverride.FILING_OVERRIDE

    override fun insert(override: NewOverride): StoredOverride {
        val id = UUID.randomUUID().toString()
        dsl
            .insertInto(t)
            .set(t.TENANT_ID, tenants.current().value)
            .set(t.ID, id)
            .set(t.ENTITY_REF, override.entityRef)
            .set(t.PERIOD_REF, override.periodRef)
            .set(t.CHECK_ID, override.checkId)
            .set(t.REASON, override.reason)
            .set(t.STATUS, override.status.name)
            .set(t.REQUESTED_BY, override.requestedBy)
            .set(t.RELEASED_BY, override.releasedBy)
            .execute()
        return StoredOverride(
            id,
            override.entityRef,
            override.periodRef,
            override.checkId,
            override.reason,
            override.status,
            override.requestedBy,
            override.releasedBy,
        )
    }

    override fun findPending(
        entityRef: String,
        periodRef: String,
        overrideId: String,
    ): StoredOverride? =
        dsl
            .selectFrom(t)
            .where(t.TENANT_ID.eq(tenants.current().value))
            .and(t.ENTITY_REF.eq(entityRef))
            .and(t.PERIOD_REF.eq(periodRef))
            .and(t.ID.eq(overrideId))
            .and(t.STATUS.eq(OverrideStatus.PENDING.name))
            .fetchOne()
            ?.let { toStored(it) }

    override fun release(
        overrideId: String,
        releasedBy: String,
    ): Boolean =
        dsl
            .update(t)
            .set(t.STATUS, OverrideStatus.RELEASED.name)
            .set(t.RELEASED_BY, releasedBy)
            .where(t.TENANT_ID.eq(tenants.current().value))
            .and(t.ID.eq(overrideId))
            .and(t.STATUS.eq(OverrideStatus.PENDING.name))
            .execute() == 1

    override fun entries(
        entityRef: String,
        periodRef: String,
    ): List<StoredOverride> =
        dsl
            .selectFrom(t)
            .where(t.TENANT_ID.eq(tenants.current().value))
            .and(t.ENTITY_REF.eq(entityRef))
            .and(t.PERIOD_REF.eq(periodRef))
            .orderBy(t.ID)
            .fetch()
            .map { toStored(it) }

    private fun toStored(r: Record): StoredOverride {
        val stored = checkNotNull(r.get(t.STATUS))
        return StoredOverride(
            id = checkNotNull(r.get(t.ID)),
            entityRef = checkNotNull(r.get(t.ENTITY_REF)),
            periodRef = checkNotNull(r.get(t.PERIOD_REF)),
            checkId = checkNotNull(r.get(t.CHECK_ID)),
            reason = checkNotNull(r.get(t.REASON)),
            status = OverrideStatus.entries.firstOrNull { it.name == stored } ?: error("stored status is unknown"),
            requestedBy = checkNotNull(r.get(t.REQUESTED_BY)),
            releasedBy = r.get(t.RELEASED_BY),
        )
    }
}
