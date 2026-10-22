package com.dynokore.gst.access.adapters.separation

import com.dynokore.gst.access.application.separation.SoleOperatorScope
import com.dynokore.gst.access.application.separation.SoleOperatorScopePolicy
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import java.time.Clock

/**
 * The tenant-scope value of VP-81 (INV-REQ-8287), set by `tenant.admin` through service-runtime's `setPolicy`: the one
 * selected option key, mapped to its constant by string equality. A key that is none of the three, an empty or longer
 * selection and a failed read throw; no other constant is ever returned in their place.
 */
class VariationPointSoleOperatorScope(
    private val reader: PolicyReader,
    private val tenants: TenantContext,
    private val clock: Clock,
) : SoleOperatorScopePolicy {
    override fun current(): SoleOperatorScope {
        val setting =
            reader.resolve(
                VP_81,
                PolicyScope.tenant(tenants.current()),
                BusinessDate.today(clock),
            )
        val key = setting.selected.single().key
        return SoleOperatorScope.entries.singleOrNull { it.name == key }
            ?: error("VP-81 selects an option this service does not know")
    }

    private companion object {
        val VP_81 = VariationPointKey("VP-81")
    }
}
