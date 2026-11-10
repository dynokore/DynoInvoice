package com.dynokore.gst.aggregation.adapters.policy

import com.dynokore.gst.aggregation.application.CadenceSource
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.LocalDate

/**
 * VP-55 (aggregation cadence, INV-REQ-1316) as the config-resolver resolves it at entity scope. Options:
 * DEFAULT_CADENCE (daily for POS and STI, monthly for PCP), POS_STI_WEEKLY and PCP_WEEKLY (weekly for that
 * category). An option that is unknown, or a selection that is not one option, is an error (fail closed).
 */
class PolicyCadenceSource(
    private val reader: PolicyReader,
    private val tenants: TenantContext,
    private val runner: TransactionRunner,
) : CadenceSource {
    override fun cadence(
        entityRef: String,
        aggregateType: AggregateType,
        businessDate: LocalDate,
    ): Cadence {
        val setting =
            runner.inTransaction {
                reader.resolve(
                    VP_55,
                    PolicyScope.entity(tenants.current(), entityRef),
                    businessDate,
                )
            }
        val option = setting.selected.single().key
        return when (option) {
            DEFAULT_CADENCE -> {
                Cadence.defaultFor(aggregateType)
            }

            POS_STI_WEEKLY -> {
                if (aggregateType ==
                    AggregateType.PCP
                ) {
                    Cadence.defaultFor(aggregateType)
                } else {
                    Cadence.WEEKLY
                }
            }

            PCP_WEEKLY -> {
                if (aggregateType == AggregateType.PCP) Cadence.WEEKLY else Cadence.defaultFor(aggregateType)
            }

            else -> {
                error("VP-55 resolved to the unknown option $option")
            }
        }
    }

    companion object {
        val VP_55 = VariationPointKey("VP-55")
        const val DEFAULT_CADENCE = "DEFAULT_CADENCE"
        const val POS_STI_WEEKLY = "POS_STI_WEEKLY"
        const val PCP_WEEKLY = "PCP_WEEKLY"
    }
}
