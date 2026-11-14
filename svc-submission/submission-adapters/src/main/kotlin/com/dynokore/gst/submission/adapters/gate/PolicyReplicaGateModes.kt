package com.dynokore.gst.submission.adapters.gate

import com.dynokore.gst.submission.application.gate.GateModes
import com.dynokore.gst.submission.domain.gate.GateMode
import com.dynokore.platform.configresolver.PolicyScope
import com.dynokore.platform.serviceruntime.replica.PolicyReplica
import com.dynokore.platform.tenancykernel.TenantContext
import java.time.Clock
import java.time.LocalDate

/**
 * [GateModes] as `VoluntaryOverSubmission` reads VP-21 (svc-rules): the variation point VP-14 of the entity, read from
 * the policy replica at the request time only, no service call. An absent value is the catalogue default ADVISORY; a
 * value that [GateMode.fromKey] refuses throws, so the call fails with nothing stored (fail closed).
 */
class PolicyReplicaGateModes(
    private val policy: PolicyReplica,
    private val tenants: TenantContext,
    private val clock: Clock,
) : GateModes {
    override fun modeOf(entityRef: String): GateMode {
        val value =
            policy.valueAt(PolicyScope.entity(tenants.current(), entityRef), VARIATION_POINT, LocalDate.now(clock))
        return value?.let { GateMode.fromKey(it.value) } ?: GateMode.ADVISORY
    }

    companion object {
        const val VARIATION_POINT = "VP-14"
    }
}
