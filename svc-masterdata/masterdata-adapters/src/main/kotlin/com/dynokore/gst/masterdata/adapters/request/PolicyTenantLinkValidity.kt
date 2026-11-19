package com.dynokore.gst.masterdata.adapters.request

import com.dynokore.gst.masterdata.application.request.TenantLinkValidity
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/**
 * VP-83 as `PolicyReader.resolve` gives it for the bound tenant on the installation's business date, read on every
 * send and never cached (INV-REQ-24775, INV-Q-9649 A). `DAYS_7` gives null, so the default of the installation limits
 * applies; `CUSTOM` gives the whole number of days of its value. The range is not enforced here: the use case refuses a
 * value below 1 or above the maximum, and the catalogue bound refuses it at the write. An option key this service does
 * not know, a missing or non-integer value, or a selection that is not exactly one option is an error that refuses the
 * send, never a guess.
 */
class PolicyTenantLinkValidity(
    private val policy: PolicyReader,
    private val tenants: TenantContext,
    private val clock: Clock,
) : TenantLinkValidity {
    override fun days(): Long? {
        val selected =
            policy
                .resolve(LINK_VALIDITY, PolicyScope.tenant(tenants.current()), BusinessDate.today(clock))
                .selected
        check(selected.size == 1) { "$LINK_VALIDITY selected ${selected.size} options, expected exactly one" }
        val option = selected.single()
        return when (option.key) {
            DEFAULT_OPTION -> null
            CUSTOM_OPTION -> customDays(checkNotNull(option.valueJson) { "$LINK_VALIDITY $CUSTOM_OPTION has no value" })
            else -> error("$LINK_VALIDITY resolved to an option this service does not know: ${option.key}")
        }
    }

    private fun customDays(valueJson: String): Long {
        val node = MAPPER.readTree(valueJson)
        check(
            node.isIntegralNumber && node.canConvertToLong(),
        ) { "$LINK_VALIDITY $CUSTOM_OPTION value is not a whole number" }
        return node.asLong()
    }

    companion object {
        /** VP-83: how many days a counterparty request link stays valid (INV-REQ-24775), tenant scope. */
        val LINK_VALIDITY = VariationPointKey("VP-83")

        private const val DEFAULT_OPTION = "DAYS_7"
        private const val CUSTOM_OPTION = "CUSTOM"
        private val MAPPER = JsonMapper()
    }
}
