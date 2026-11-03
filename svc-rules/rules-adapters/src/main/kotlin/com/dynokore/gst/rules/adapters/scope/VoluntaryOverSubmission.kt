package com.dynokore.gst.rules.adapters.scope

import com.dynokore.gst.rules.domain.resolution.TransactionDate
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.tenancykernel.TenantId

/**
 * The entity's voluntary over-submission election, variation point VP-21 (INV-REQ-375, INV-REQ-1282), read from the
 * policy replica only: no service call and no constant. It is on only when the value in force on the transaction date
 * is exactly the option key `ON`; an absent value, any other value, and a value of another key or entity are not
 * applied (fail closed, INV-AC-2268).
 */
class VoluntaryOverSubmission(
    private val policy: PolicyReplica,
) {
    fun isOn(
        tenant: TenantId,
        entityRef: String,
        date: TransactionDate,
    ): Boolean =
        !tenant.isPlatform &&
            policy.valueAt(PolicyScope.entity(tenant, entityRef), VARIATION_POINT, date.value)?.value == ON

    companion object {
        const val VARIATION_POINT = "VP-21"
        const val ON = "ON"
    }
}
