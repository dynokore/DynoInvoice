package com.dynokore.gst.access.adapters.approval

import com.dynokore.platform.configresolver.ConstraintVerdict
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SettingTarget
import com.dynokore.platform.configresolver.ValueConstraints
import com.dynokore.platform.configresolver.VariationPointKey
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDate

/**
 * What only access-svc can check in the value of VP-32 (INV-REQ-1293, INV-AC-9561): the amount of
 * SECOND_APPROVER_ABOVE_AMOUNT is a JSON number above 0, so a threshold of 0, a negative one, a text and a missing one
 * are refused. Any other variation point is not this service's to check here and gets no verdict.
 */
class OverrideAmountConstraints(
    private val mapper: ObjectMapper,
) : ValueConstraints {
    override fun check(
        target: SettingTarget,
        key: VariationPointKey,
        selected: List<SelectedOption>,
        onDate: LocalDate,
    ): ConstraintVerdict? =
        if (key == VP_32) {
            verdictOf(selected.singleOrNull { it.key == ABOVE_AMOUNT }?.let { it.valueJson.orEmpty() })
        } else {
            null
        }

    private fun verdictOf(json: String?): ConstraintVerdict {
        if (json == null) return ConstraintVerdict.Accept
        val value =
            runCatching {
                val node = mapper.readTree(json)
                if (node.isNumber) node.decimalValue() else null
            }.getOrNull()
        return if (value != null && value.compareTo(BigDecimal.ZERO) > 0) {
            ConstraintVerdict.Accept
        } else {
            ConstraintVerdict.Refuse("the amount of SECOND_APPROVER_ABOVE_AMOUNT is a number above 0")
        }
    }

    private companion object {
        val VP_32 = VariationPointKey("VP-32")
        const val ABOVE_AMOUNT = "SECOND_APPROVER_ABOVE_AMOUNT"
    }
}
