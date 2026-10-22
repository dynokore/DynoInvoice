package com.dynokore.gst.access.adapters.credential

import com.dynokore.platform.configresolver.PolicyChange
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyRefusal
import com.dynokore.platform.configresolver.PolicyRefused
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.PolicyWriter
import com.dynokore.platform.configresolver.SetPolicyCommand
import com.dynokore.platform.configresolver.VariationPointKey
import java.time.LocalDate
import java.util.function.Supplier

/**
 * VP-77 is tighten-only (INV-REQ-2760): `client_secret_basic` may be switched off, never back on once an installation
 * has switched it off. The platform writer only refuses an option marked LOOSER, and SAAS_STANDARD is the default
 * option, so this service, which owns VP-77, refuses it here: a selection of SAAS_STANDARD is refused when
 * REGULATED_PRIVATE is stored for the installation, on the effective date of the request or on any later date. Every
 * other command passes to [delegate] unchanged.
 */
class TightenOnlyClientSecretWriter(
    private val delegate: PolicyWriter,
    private val reader: Supplier<PolicyReader>,
) : PolicyWriter by delegate {
    override fun setPolicy(command: SetPolicyCommand): PolicyChange {
        if (command.key == VP_77 && command.selected.any { it.key == SAAS_STANDARD } && switchedOff(command)) {
            throw PolicyRefused(
                PolicyRefusal.LOOSER_AT_INSTALLATION,
                VP_77,
                "client_secret_basic is switched off for this installation and is never switched back on",
            )
        }
        return delegate.setPolicy(command)
    }

    private fun switchedOff(command: SetPolicyCommand): Boolean =
        listOf(command.effectiveFrom, LAST_DATE).any { date ->
            reader
                .get()
                .resolve(VP_77, PolicyScope.installation(), date)
                .selected
                .any { it.key == REGULATED_PRIVATE }
        }

    private companion object {
        val VP_77 = VariationPointKey("VP-77")
        const val SAAS_STANDARD = "SAAS_STANDARD"
        const val REGULATED_PRIVATE = "REGULATED_PRIVATE"
        val LAST_DATE: LocalDate = LocalDate.of(9999, 12, 31)
    }
}
