package com.dynokore.gst.access.adapters.consent

import com.dynokore.gst.access.application.consent.AgentTenancy
import com.dynokore.platform.configresolver.BusinessDate
import com.dynokore.platform.configresolver.ConstraintVerdict
import com.dynokore.platform.configresolver.ElectionRequired
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.configresolver.SelectedOption
import com.dynokore.platform.configresolver.SettingTarget
import com.dynokore.platform.configresolver.ValueConstraints
import com.dynokore.platform.configresolver.VariationPointKey
import java.time.Clock
import java.time.LocalDate

/** The two options of VP-74. */
private const val ON = "ON"
private const val OFF = "OFF"

internal val VP_74 = VariationPointKey("VP-74")

/**
 * Whether agent tenancy is on (VP-74, INV-REQ-2757), read at every call from the installation setting. With no value
 * set the default of the installation form stands: on in SaaS, off in a private installation (behaviour 2). Any value
 * other than exactly ON answers off, and a failure to read is not answered with a default: it propagates, so the
 * operation that asked (recording a consent) fails and records nothing.
 */
class VariationPointAgentTenancy(
    private val reader: PolicyReader,
    private val clock: Clock,
    private val saas: Boolean,
) : AgentTenancy {
    override fun enabled(): Boolean =
        try {
            reader
                .resolve(VP_74, PolicyScope.installation(), BusinessDate.today(clock))
                .selected
                .singleOrNull()
                ?.key == ON
        } catch (_: ElectionRequired) {
            saas
        }
}

/** Whether a consent is live anywhere in the installation: the switch-off check reads across tenants. */
fun interface LiveConsentProbe {
    fun anyLive(): Boolean
}

/**
 * What only access-svc can check in the value of VP-74 (INV-REQ-2757 behaviour 4): switching agent tenancy off is
 * refused while any agent consent is live. Any other variation point is not this check's to judge and gets no verdict.
 */
class AgentTenancyConstraints(
    private val probe: LiveConsentProbe,
) : ValueConstraints {
    override fun check(
        target: SettingTarget,
        key: VariationPointKey,
        selected: List<SelectedOption>,
        onDate: LocalDate,
    ): ConstraintVerdict? =
        when {
            key != VP_74 -> {
                null
            }

            selected.singleOrNull()?.key != OFF -> {
                ConstraintVerdict.Accept
            }

            probe.anyLive() -> {
                ConstraintVerdict.Refuse(
                    "agent tenancy cannot be switched off while an agent consent is live",
                )
            }

            else -> {
                ConstraintVerdict.Accept
            }
        }
}
