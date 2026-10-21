package com.dynokore.gst.transport.adapters.persistence

import com.dynokore.gst.transport.adapters.persistence.jooq.tables.EntityAdapter.Companion.ENTITY_ADAPTER
import com.dynokore.gst.transport.application.EntityAdapterAssignments
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import java.time.Clock
import java.time.OffsetDateTime

/** The adapter assignment of each entity of the bound tenant (INV-REQ-796). */
class JooqEntityAdapterAssignments(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
    private val tenants: TenantContext,
    private val clock: Clock,
) : EntityAdapterAssignments {
    override fun adapterFor(entity: EntityRef): AdapterId? =
        runner.inTransaction {
            dsl
                .select(ENTITY_ADAPTER.ADAPTER_ID)
                .from(ENTITY_ADAPTER)
                .where(ENTITY_ADAPTER.ENTITY_REF.eq(entity.value))
                .fetchOne(ENTITY_ADAPTER.ADAPTER_ID)
                ?.let(::AdapterId)
        }

    override fun assign(
        entity: EntityRef,
        adapter: AdapterId,
    ) {
        val now = OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC)
        runner.inTransaction {
            dsl
                .insertInto(ENTITY_ADAPTER)
                .set(ENTITY_ADAPTER.TENANT_ID, tenants.current().value)
                .set(ENTITY_ADAPTER.ENTITY_REF, entity.value)
                .set(ENTITY_ADAPTER.ADAPTER_ID, adapter.value)
                .set(ENTITY_ADAPTER.ASSIGNED_AT, now)
                .onConflict(ENTITY_ADAPTER.TENANT_ID, ENTITY_ADAPTER.ENTITY_REF)
                .doUpdate()
                .set(ENTITY_ADAPTER.ADAPTER_ID, adapter.value)
                .set(ENTITY_ADAPTER.ASSIGNED_AT, now)
                .execute()
        }
    }
}
