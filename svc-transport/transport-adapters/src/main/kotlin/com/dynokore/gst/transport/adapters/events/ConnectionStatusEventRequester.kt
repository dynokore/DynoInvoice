package com.dynokore.gst.transport.adapters.events

import com.dynokore.gst.transport.application.ConnectionState
import com.dynokore.gst.transport.application.ConnectionStatusEvents
import com.dynokore.gst.transport.domain.TenantRef
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.serviceruntime.connection.CircuitBreakerState
import com.dynokore.platform.serviceruntime.connection.ConnectionFailure
import com.dynokore.platform.serviceruntime.connection.ConnectionRecord
import com.dynokore.platform.serviceruntime.connection.ConnectionStatusReporter
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.util.UUID

/**
 * Requests `ConnectionStatusChanged` (INV-REQ-836 b3-b4, INV-REQ-835 b5) through the one shared publisher of
 * service-runtime, never a writer of its own. The state is passed on as the breaker state it came from (UP is closed,
 * DEGRADED half-open, DOWN open); the publisher writes the event under the connection's tenant and tenant-wide entity.
 * [recordOf] names the connection: endpoint, environment tag and credential reference of the adapter of
 * [connectionRef].
 */
class ConnectionStatusEventRequester(
    private val reporter: ConnectionStatusReporter,
    private val recordOf: (TenantId, String) -> ConnectionRecord,
    private val clock: Clock,
) : ConnectionStatusEvents {
    override fun requestChanged(
        tenant: TenantRef,
        connectionRef: String,
        state: ConnectionState,
        lastFailureReason: String?,
    ) {
        val tenantId = TenantId(UUID.fromString(tenant.value))
        val breaker =
            when (state) {
                ConnectionState.UP -> CircuitBreakerState.CLOSED
                ConnectionState.DEGRADED -> CircuitBreakerState.HALF_OPEN
                ConnectionState.DOWN -> CircuitBreakerState.OPEN
            }
        reporter.report(
            recordOf(tenantId, connectionRef),
            breaker,
            lastFailureReason?.let { ConnectionFailure(clock.instant(), it) },
        )
    }

    companion object {
        /** The connection record of an AP adapter: its name is the adapter id, the owner service is transport-svc. */
        fun record(
            tenant: TenantId,
            adapterId: String,
            endpoint: String,
            environment: String,
            credential: SecretRef,
        ): ConnectionRecord =
            ConnectionRecord(adapterId, endpoint, credential, environment, OWNER, tenant, EntityRef.TENANT_WIDE)

        const val OWNER = "transport-svc"
    }
}
