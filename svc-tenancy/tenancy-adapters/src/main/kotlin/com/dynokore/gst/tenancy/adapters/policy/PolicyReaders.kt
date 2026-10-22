package com.dynokore.gst.tenancy.adapters.policy

import com.dynokore.gst.tenancy.application.status.GroupElectionReader
import com.dynokore.gst.tenancy.application.status.ImplementationBasisReader
import com.dynokore.gst.tenancy.application.status.ResolvedImplementation
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate

/** The variation points tenancy-svc owns and reads through config-resolver (CS-28, TPL-03). */
object TenancyVariationPoints {
    /** VP-28: how a GST group transmits (planned: the default only, INV-Q-8729 A). */
    val GROUP_TRANSMISSION = VariationPointKey("VP-28")

    /** VP-51: the implementation date basis (INV-REQ-1312). */
    val IMPLEMENTATION_DATE_BASIS = VariationPointKey("VP-51")

    /** VP-64: whether a GST group transmits an excluded member's data (INV-REQ-1547). */
    val EXCLUDED_MEMBER_ELECTION = VariationPointKey("VP-64")

    const val MANDATORY_DATE = "MANDATORY_DATE"
    const val EARLIER_VOLUNTARY_DATE = "EARLIER_VOLUNTARY_DATE"
    const val IRAS_COMMUNICATED_DATE = "IRAS_COMMUNICATED_DATE"
    const val TRANSMIT_AND_GO_LIVE = "TRANSMIT_AND_GO_LIVE"
    const val OMIT_EXCLUDED = "OMIT_EXCLUDED"
}

/** VP-51 as resolved by PolicyReader for the entity inside its parent group, on the transaction date. */
class PolicyImplementationBasisReader(
    private val policy: PolicyReader,
    private val tenants: TenantContext,
    private val mapper: ObjectMapper,
) : ImplementationBasisReader {
    override fun resolve(
        groupRef: String,
        entityRef: String,
        on: LocalDate,
    ): ResolvedImplementation {
        val scope = PolicyScope.entity(tenants.current(), entityRef, group = groupRef)
        val option = policy.resolve(TenancyVariationPoints.IMPLEMENTATION_DATE_BASIS, scope, on).selected.single()
        return when (option.key) {
            TenancyVariationPoints.MANDATORY_DATE -> {
                ResolvedImplementation.MandatoryDate
            }

            TenancyVariationPoints.EARLIER_VOLUNTARY_DATE -> {
                ResolvedImplementation.EarlierVoluntaryDate(LocalDate.parse(valueOf(option).asString()))
            }

            TenancyVariationPoints.IRAS_COMMUNICATED_DATE -> {
                val record = valueOf(option)
                ResolvedImplementation.IrasCommunicatedDate(
                    LocalDate.parse(record["date"].asString()),
                    record["iras_reference"].asString(),
                )
            }

            else -> {
                error("VP-51 resolved to an option this service does not know: ${option.key}")
            }
        }
    }

    private fun valueOf(option: SelectedOption) =
        mapper.readTree(checkNotNull(option.valueJson) { "${option.key} has no value" })
}

/** VP-64 as resolved by PolicyReader for the parent group, on the date (default: omit the excluded member's data). */
class PolicyGroupElectionReader(
    private val policy: PolicyReader,
    private val tenants: TenantContext,
) : GroupElectionReader {
    override fun transmitsExcludedMember(
        groupRef: String,
        on: LocalDate,
    ): Boolean {
        val scope = PolicyScope.tenant(tenants.current(), group = groupRef)
        val option = policy.resolve(TenancyVariationPoints.EXCLUDED_MEMBER_ELECTION, scope, on).selected.single()
        return when (option.key) {
            TenancyVariationPoints.TRANSMIT_AND_GO_LIVE -> true
            TenancyVariationPoints.OMIT_EXCLUDED -> false
            else -> error("VP-64 resolved to an option this service does not know: ${option.key}")
        }
    }
}
