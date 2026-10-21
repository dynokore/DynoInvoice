package com.dynokore.gst.access.adapters.landing

import com.dynokore.gst.access.application.landing.RolePrecedence
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * The tenant-scope role precedence of VP-73, set by `tenant.admin` through service-runtime's `setPolicy`
 * (INV-REQ-2756 behaviour 1): the ordered role list of the option TENANT_PRECEDENCE, or null while the preset
 * ROLE_ORDER_PRESET stands. A key that is no role is ignored, so a stale list cannot name a role of this service.
 */
class VariationPointRolePrecedence(
    private val reader: PolicyReader,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val mapper: ObjectMapper,
) : RolePrecedence {
    override fun tenantPrecedence(): List<Role>? {
        val setting =
            reader.resolve(
                VP_73,
                PolicyScope.tenant(tenants.current()),
                BusinessDate.today(clock),
            )
        val json = setting.selected.singleOrNull { it.key == TENANT_PRECEDENCE }?.valueJson ?: return null
        return mapper.readTree(json).mapNotNull { Role.ofWire(it.asString()) }
    }

    private companion object {
        val VP_73 = VariationPointKey("VP-73")
        const val TENANT_PRECEDENCE = "TENANT_PRECEDENCE"
    }
}
