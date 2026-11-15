package com.dynokore.gst.tenancy.adapters.persistence

import com.dynokore.gst.tenancy.adapters.persistence.jooq.tables.references.ENTITY
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.util.UUID

/**
 * The id of the `tenancy.entity` row of an entity reference (jOOQ only, CS-18), read in the caller's transaction, where
 * an entity created in the same transaction is visible. [tenants] is the bound context the connection's row-level
 * security reads; the tenant condition is also explicit so the answer never depends on the binding alone.
 */
class JooqEntityIdLookup(
    private val dsl: DSLContext,
    @Suppress("unused") private val tenants: TenantContext,
) {
    /** The row id of the entity [entityRef] of [tenant], or null when the tenant has no such entity. */
    fun lookup(
        tenant: UUID,
        entityRef: String,
    ): UUID? =
        dsl
            .select(ENTITY.ID)
            .from(ENTITY)
            .where(ENTITY.TENANT_ID.eq(tenant).and(ENTITY.REF.eq(entityRef)))
            .fetchOne(ENTITY.ID)
}
