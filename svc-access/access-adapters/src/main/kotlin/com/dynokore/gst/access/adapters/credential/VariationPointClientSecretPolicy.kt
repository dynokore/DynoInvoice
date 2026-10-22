package com.dynokore.gst.access.adapters.credential

import com.dynokore.gst.access.application.principal.ClientSecretPolicy
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.VariationPointKey
import java.time.Clock

/**
 * VP-77 at installation scope as the config-resolver resolves it (INV-REQ-2760): `client_secret_basic` is available
 * under the default preset SAAS_STANDARD and switched off under REGULATED_PRIVATE. Any other selected option, or no
 * option, is read as off only when it is REGULATED_PRIVATE; the platform writer already refuses to loosen the value.
 */
class VariationPointClientSecretPolicy(
    private val reader: PolicyReader,
    private val clock: Clock,
) : ClientSecretPolicy {
    override fun clientSecretBasicAllowed(): Boolean =
        reader
            .resolve(VP_77, PolicyScope.installation(), BusinessDate.today(clock))
            .selected
            .none { it.key == REGULATED_PRIVATE }

    private companion object {
        val VP_77 = VariationPointKey("VP-77")
        const val REGULATED_PRIVATE = "REGULATED_PRIVATE"
    }
}
