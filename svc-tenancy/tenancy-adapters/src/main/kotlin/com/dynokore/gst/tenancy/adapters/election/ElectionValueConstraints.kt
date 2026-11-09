package com.dynokore.gst.tenancy.adapters.election

import com.dynokore.platform.configresolver.ConstraintVerdict
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SettingTarget
import com.dynokore.platform.configresolver.ValueConstraints
import com.dynokore.platform.configresolver.VariationPointKey
import java.time.LocalDate

/**
 * The ValueConstraints port of tenancy-svc over the election variation points: VP-22, VP-24 and VP-25 name this port
 * in the catalogue, so it must not refuse them; who may write them is decided by [ElectionPolicyWriterGuard] in front
 * of the writer (ruling INV-Q-10023), not here. VP-76 is checked by config-resolver alone (its bounds are in the
 * catalogue); any other key goes to [others].
 */
class ElectionValueConstraints(
    private val others: ValueConstraints,
) : ValueConstraints {
    override fun check(
        target: SettingTarget,
        key: VariationPointKey,
        selected: List<SelectedOption>,
        onDate: LocalDate,
    ): ConstraintVerdict? =
        if (key in ElectionPolicyWriterGuard.ELECTION_KEYS || key == ROUNDING_BASIS) {
            ConstraintVerdict.Accept
        } else {
            others.check(target, key, selected, onDate)
        }

    companion object {
        /** VP-76: the rounding basis, set by the shared setPolicy (role tax.approver) and read as one option. */
        val ROUNDING_BASIS = VariationPointKey("VP-76")
    }
}
