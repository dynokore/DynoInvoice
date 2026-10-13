package com.dynokore.platform.tenancyjooq

import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId

internal object BoundTenant {
    /** The bound tenant; a statement without a binding is refused. */
    fun require(tenants: TenantContext): TenantId = try {
        tenants.current()
    } catch (unbound: IllegalStateException) {
        throw TenantPredicateViolation("no tenant is bound on this thread", unbound)
    }
}
