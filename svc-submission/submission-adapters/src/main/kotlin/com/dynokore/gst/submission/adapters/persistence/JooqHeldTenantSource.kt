package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.HeldTenants
import com.dynokore.gst.submission.application.ordering.HeldTenantSource
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext

/**
 * [HeldTenantSource] over `submission.held_tenants` (INV-REQ-709): tenant ids only, read outside any tenant
 * transaction; the function reads across tenants as its definer, like [JooqWaitingTenantSource]'s.
 */
class JooqHeldTenantSource(
    private val dsl: DSLContext,
) : HeldTenantSource {
    override fun tenants(): List<TenantId> {
        val held = HeldTenants.HELD_TENANTS.call()
        return dsl
            .select(held.HELD_TENANT)
            .from(held)
            .fetch()
            .map { TenantId(checkNotNull(it.value1())) }
    }
}
