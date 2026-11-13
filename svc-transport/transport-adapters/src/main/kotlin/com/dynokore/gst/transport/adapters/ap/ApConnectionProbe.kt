package com.dynokore.gst.transport.adapters.ap

import com.dynokore.gst.transport.application.AccessPointAdapters
import com.dynokore.gst.transport.application.ApProbe
import com.dynokore.gst.transport.application.ConnectionNotFound
import com.dynokore.gst.transport.application.ProbeOutcome
import com.dynokore.gst.transport.application.TestClass
import com.dynokore.gst.transport.domain.AdapterId
import com.dynokore.gst.transport.domain.TenantRef

/**
 * The AP probe of "Test connection" (INV-REQ-835 b2): the call to the access point goes through the circuit breaker of
 * its adapter. While the breaker is open and its wait has not run out, the AP is not called and the outcome is
 * UNREACHABLE with the detail code `CIRCUIT_OPEN` (fail fast, as an open breaker does for every call); otherwise the
 * adapter probes the AP. The use case records the outcome in the same breaker. A ref that names no configured adapter
 * is not found.
 */
class ApConnectionProbe(
    private val adapters: AccessPointAdapters,
    private val breaker: AdapterCircuitBreaker,
) : ApProbe {
    override fun probe(
        tenant: TenantRef,
        connectionRef: String,
    ): ProbeOutcome {
        val adapter =
            adapters.find(AdapterId(connectionRef)) as? HttpAccessPointAdapter
                ?: throw ConnectionNotFound(connectionRef)
        return if (breaker.permits(
                connectionRef,
            )
        ) {
            adapter.probe()
        } else {
            ProbeOutcome(TestClass.UNREACHABLE, CIRCUIT_OPEN)
        }
    }

    companion object {
        const val CIRCUIT_OPEN = "CIRCUIT_OPEN"
    }
}
