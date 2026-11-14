package com.dynokore.gst.rules.adapters.scope

import com.dynokore.gst.rules.application.SilenceLimitPort
import com.dynokore.gst.rules.domain.pack.SilenceLimit
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import java.time.Clock
import java.time.LocalDate

/**
 * The DEP-001 silence limit, variation point VP-72 (INV-REQ-2468), read from the policy replica at installation scope
 * and nowhere else: it is written only through the mounted shared `setPolicy`, which refuses a value above 30 days and,
 * while VP-72 is planned, any value but 7. A value outside 7 to 30 days is never applied here (the default stands).
 */
class PolicySilenceLimit(
    private val policy: PolicyReplica,
    private val clock: Clock,
) : SilenceLimitPort {
    override fun limit(): SilenceLimit =
        SilenceLimit.ofPolicyValue(
            policy.valueAt(PolicyScope.installation(), VARIATION_POINT, LocalDate.now(clock))?.value,
        )

    companion object {
        const val VARIATION_POINT = "VP-72"
    }
}
