package com.dynokore.gst.access.adapters.approval

import com.dynokore.gst.access.application.approval.ApprovalSettings
import com.dynokore.gst.access.domain.approval.OverridePolicy
import com.dynokore.gst.access.domain.approval.OverrideReleaseOption
import com.dynokore.gst.access.domain.approval.PackApprovalOption
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import com.dynokore.platform.tenancykernel.TenantContext
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Clock

/**
 * VP-03, VP-04 and VP-32 as `setPolicy` of service-runtime stores them and the config-resolver resolves them as of the
 * business date (INV-REQ-1264, INV-REQ-1265, INV-REQ-1293). Each value is read at the scope of its own entity only, so
 * the value of entity A never answers for entity B. A selection that is not exactly one option of the row, an option
 * this service does not know, and a failed read throw: no other value is ever returned in their place. A look-alike
 * key such as `SOLE_OPERATOR_ACKS` maps to no option (exact string equality).
 */
class VariationPointApprovalSettings(
    private val reader: PolicyReader,
    private val tenants: TenantContext,
    private val clock: Clock,
    private val mapper: ObjectMapper,
) : ApprovalSettings {
    override fun staffOption(entity: EntityRef): PackApprovalOption =
        packOption(VP_03, PolicyScope.entity(tenants.current(), entity.value), STAFF_OPTIONS)

    override fun agentOption(client: EntityRef): PackApprovalOption =
        packOption(
            VP_04,
            PolicyScope.entity(tenants.current(), client.value, clientEntity = client.value),
            AGENT_OPTIONS,
        )

    override fun overridePolicy(entity: EntityRef): OverridePolicy {
        val setting = resolve(VP_32, PolicyScope.entity(tenants.current(), entity.value))
        val selected = setting.selected.single()
        val option =
            OverrideReleaseOption.entries.singleOrNull { it.name == selected.key }
                ?: error("VP-32 selects an option this service does not know")
        val threshold =
            if (option == OverrideReleaseOption.SECOND_APPROVER_ABOVE_AMOUNT) {
                amountOf(checkNotNull(selected.valueJson) { "VP-32 SECOND_APPROVER_ABOVE_AMOUNT holds no amount" })
            } else {
                null
            }
        return OverridePolicy(option, threshold)
    }

    private fun packOption(
        key: VariationPointKey,
        scope: PolicyScope,
        known: Set<PackApprovalOption>,
    ): PackApprovalOption {
        val chosen = resolve(key, scope).selected.single().key
        return known.singleOrNull { it.name == chosen }
            ?: error("${key.value} selects an option this service does not know")
    }

    private fun resolve(
        key: VariationPointKey,
        scope: PolicyScope,
    ) = reader.resolve(key, scope, BusinessDate.today(clock))

    private fun amountOf(json: String): BigDecimal {
        val node = mapper.readTree(json)
        return when {
            node.isNumber -> node.decimalValue()
            node.isString -> BigDecimal(node.asString())
            else -> error("VP-32 holds an amount that is not a number")
        }
    }

    private companion object {
        val VP_03 = VariationPointKey("VP-03")
        val VP_04 = VariationPointKey("VP-04")
        val VP_32 = VariationPointKey("VP-32")
        val STAFF_OPTIONS =
            setOf(
                PackApprovalOption.STAFF_APPROVER,
                PackApprovalOption.EXTERNAL_ADVISOR,
                PackApprovalOption.NON_FINANCE_OFFICER,
                PackApprovalOption.SOLE_OPERATOR_ACK,
            )
        val AGENT_OPTIONS =
            setOf(
                PackApprovalOption.AGENT_SECOND_PERSON_CLIENT_ACK,
                PackApprovalOption.AGENT_SECOND_PERSON_CLIENT_APPROVAL,
                PackApprovalOption.CLIENT_APPROVER_ALONE,
            )
    }
}
