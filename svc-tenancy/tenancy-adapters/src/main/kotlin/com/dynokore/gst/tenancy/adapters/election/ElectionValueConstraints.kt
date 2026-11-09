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
 * catalogue); VP-52 is allowlisted to the one option of the catalogue row, [CUSTOMER_KUBERNETES] (INV-REQ-1313, OWASP
 * ASVS V5.1.3, deny by default); any other key goes to [others].
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
        when {
            key in ElectionPolicyWriterGuard.ELECTION_KEYS || key == ROUNDING_BASIS -> ConstraintVerdict.Accept
            key == PLATFORM_ELECTION -> platformVerdict(selected)
            else -> others.check(target, key, selected, onDate)
        }

    private fun platformVerdict(selected: List<SelectedOption>): ConstraintVerdict =
        if (selected.size == 1 && selected.single().key == CUSTOMER_KUBERNETES) {
            ConstraintVerdict.Accept
        } else {
            ConstraintVerdict.Refuse("VP-52 accepts exactly one option, $CUSTOMER_KUBERNETES")
        }

    companion object {
        /** VP-76: the rounding basis, set by the shared setPolicy (role tax.approver) and read as one option. */
        val ROUNDING_BASIS = VariationPointKey("VP-76")

        /** VP-52: the platform a private installation elects at qualification (INV-REQ-1313). */
        val PLATFORM_ELECTION = VariationPointKey("VP-52")

        /** The only option of the VP-52 catalogue row while the supplied appliance is deferred. */
        const val CUSTOMER_KUBERNETES = "CUSTOMER_KUBERNETES"
    }
}
