package com.dynokore.gst.tenancy.adapters.election

import com.dynokore.gst.tenancy.domain.ElectionKind
import com.dynokore.platform.configresolver.ConstraintVerdict
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SettingTarget
import com.dynokore.platform.configresolver.ValueConstraints
import com.dynokore.platform.configresolver.VariationPointKey
import java.time.LocalDate

/**
 * The ValueConstraints port of tenancy-svc over the election variation points (ruling INV-Q-9692): VP-22, VP-23, VP-24
 * and VP-25 are accepted only while [ElectionWriteMarker] is set for that key, i.e. from the election operations, and
 * refused (422 POL-014) on the shared `setPolicy`. VP-76 is checked by config-resolver alone (its bounds are in the
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
        when {
            key in ELECTION_KEYS -> {
                if (ElectionWriteMarker.allows(key)) {
                    ConstraintVerdict.Accept
                } else {
                    ConstraintVerdict.Refuse(
                        "$key is set through the election operations of tenancy-svc, not setPolicy",
                    )
                }
            }

            key == ROUNDING_BASIS -> {
                ConstraintVerdict.Accept
            }

            else -> {
                others.check(target, key, selected, onDate)
            }
        }

    companion object {
        val ELECTION_KEYS: Set<VariationPointKey> =
            ElectionKind.entries
                .map {
                    VariationPointKey(
                        it.variationPoint,
                    )
                }.toSet()

        /** VP-76: the rounding basis, set by the shared setPolicy (role tax.approver) and read as one option. */
        val ROUNDING_BASIS = VariationPointKey("VP-76")
    }
}
