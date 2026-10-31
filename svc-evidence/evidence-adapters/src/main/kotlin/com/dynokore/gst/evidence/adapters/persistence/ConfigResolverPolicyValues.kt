package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.catalogue.VariationPoint
import com.dynokore.gst.evidence.application.cases.PolicyValues
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantId
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

/**
 * The response period and notice lead of VP-63 (INV-REQ-1401, INV-REQ-1403; INV-Q-6836 A). The pack period is the
 * `responseDays` default of the option PACK_DEFAULT_30D in the gst-catalogue row, so the service holds no constant 30.
 * The tenant period is `responseDays` of the tenant's SHORTER value as the [PolicyReader] resolves it on the date, or
 * null when the tenant set none. The lead is the effective `noticeLeadDays` of the selected option, else the catalogue
 * default of that option.
 */
class ConfigResolverPolicyValues(
    private val reader: PolicyReader,
) : PolicyValues {
    private val mapper = JsonMapper()

    override fun packResponseDays(): Int = catalogueDefault(PACK_DEFAULT, RESPONSE_DAYS)

    override fun tenantResponseDays(
        tenant: TenantId,
        on: LocalDate,
    ): Int? {
        val shorter = selected(tenant, on)?.takeIf { it.key == SHORTER } ?: return null
        return checkNotNull(storedDays(shorter, RESPONSE_DAYS)) { "the VP-63 SHORTER value holds no responseDays" }
    }

    override fun noticeLeadDays(
        tenant: TenantId,
        on: LocalDate,
    ): Int {
        val option = selected(tenant, on)
        return option?.let { storedDays(it, NOTICE_LEAD_DAYS) } ?: catalogueDefault(option?.key ?: PACK_DEFAULT, NOTICE_LEAD_DAYS)
    }

    private fun selected(
        tenant: TenantId,
        on: LocalDate,
    ): SelectedOption? = reader.resolve(VP_63_KEY, PolicyScope.tenant(tenant), on).selected.firstOrNull()

    private fun storedDays(
        option: SelectedOption,
        field: String,
    ): Int? {
        val node = option.valueJson?.let { mapper.readTree(it) }?.get(field) ?: return null
        check(node.isIntegralNumber) { "the VP-63 $field must be a whole number of days" }
        return node.asInt()
    }

    private fun catalogueDefault(
        option: String,
        field: String,
    ): Int {
        val spec = checkNotNull(VariationPoint.VP_63.optionSpecs?.firstOrNull { it.key == option }) { "VP-63 has no option $option" }
        val default = spec.typedValue?.fields?.get(field)?.default?.number
        return checkNotNull(default) { "VP-63 option $option holds no default for $field" }.intValueExact()
    }

    private companion object {
        val VP_63_KEY = VariationPointKey("VP-63")
        const val PACK_DEFAULT = "PACK_DEFAULT_30D"
        const val SHORTER = "SHORTER"
        const val RESPONSE_DAYS = "responseDays"
        const val NOTICE_LEAD_DAYS = "noticeLeadDays"
    }
}
