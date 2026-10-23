package com.dynokore.gst.access.adapters.breakglass

import com.dynokore.gst.access.application.breakglass.BreakGlassSetting
import com.dynokore.gst.access.application.breakglass.BreakGlassSettings
import com.dynokore.gst.access.domain.breakglass.BreakGlassPairing
import com.dynokore.gst.access.domain.breakglass.BreakGlassRules
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * VP-08 as the config-resolver resolves it for the bound tenant (INV-REQ-1269 behaviour 1): the `window` field of the
 * selected option's value, in hours (4 when no value is stored), and the pairing: the default pair, or for the option
 * OTHER_NAMED_PAIR the two roles of its `pair` field. A stored length outside 1 to 8, or a pair that is not two known
 * roles, is not used (fail closed): the unlock then refuses to open rather than open a longer or a looser session.
 */
class VariationPointBreakGlassSettings(
    private val reader: PolicyReader,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val mapper: ObjectMapper,
) : BreakGlassSettings {
    override fun current(): BreakGlassSetting {
        val setting = reader.resolve(VP_08, PolicyScope.tenant(tenants.current()), BusinessDate.today(clock))
        val selected = setting.selected.firstOrNull()
        val value = selected?.valueJson?.let { mapper.readTree(it) }
        val window = value?.get(WINDOW)
        check(window == null || window.isIntegralNumber) { "the VP-08 window must be a whole number of hours" }
        val pairing =
            if (selected?.key == OTHER_NAMED_PAIR) pairOf(value?.get(PAIR)) else BreakGlassPairing.DEFAULT
        return BreakGlassSetting(window?.asInt() ?: BreakGlassRules.DEFAULT_HOURS, pairing)
    }

    private fun pairOf(node: JsonNode?): BreakGlassPairing {
        check(node != null && node.isArray && node.size() == 2) { "the VP-08 pair must name exactly two roles" }
        val first = roleOf(node.get(0))
        val second = roleOf(node.get(1))
        return BreakGlassPairing.named(first, second)
    }

    private fun roleOf(item: JsonNode): Role =
        checkNotNull(Role.ofWire(item.asString())) { "the VP-08 pair names an unknown role" }

    private companion object {
        val VP_08 = VariationPointKey("VP-08")
        const val WINDOW = "window"
        const val PAIR = "pair"
        const val OTHER_NAMED_PAIR = "OTHER_NAMED_PAIR"
    }
}
