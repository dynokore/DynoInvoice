package com.dynokore.gst.masterdata.application.connection

import com.dynokore.gst.masterdata.domain.connection.CallRecord
import com.dynokore.gst.masterdata.domain.connection.ConnectionHealth
import com.dynokore.gst.masterdata.domain.connection.ConnectionState
import com.dynokore.gst.masterdata.domain.connection.HealthSettings
import com.dynokore.gst.masterdata.domain.connection.LastFailure
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Instant

/** The recorded calls of a connection (written by the adapter runtime; read here). */
interface CallRecords {
    /** Calls with `from <= at <= to`, oldest first. */
    fun between(
        connectionRef: String,
        from: Instant,
        to: Instant,
    ): List<CallRecord>

    /** The newest failed call ever recorded, or null. */
    fun lastFailure(connectionRef: String): LastFailure?

    /** True once any call of the connection has been recorded (an adapter that has not yet checked has none). */
    fun hasAny(connectionRef: String): Boolean
}

/** The state last requested for a connection of this instance; null before the first request. */
interface ConnectionStateMemory {
    fun lastRequested(connectionRef: String): ConnectionState?

    fun remember(
        connectionRef: String,
        state: ConnectionState,
    )
}

/** The stores a health computation reads. */
data class HealthPorts(
    val store: ConnectionStore,
    val calls: CallRecords,
)

/** The payload of `ConnectionStatusChanged`: connection, state, last failure (events.yaml). */
data class ConnectionStatusChanged(
    val connectionRef: String,
    val state: ConnectionState,
    val lastFailure: LastFailure?,
)

/** Requests the `ConnectionStatusChanged` event; the publish itself is wired by INV-T-18776. */
fun interface ConnectionStatusPublisher {
    fun publish(event: ConnectionStatusChanged)
}

sealed interface GetConnectionHealthOutcome {
    data class Found(
        val health: ConnectionHealth,
    ) : GetConnectionHealthOutcome

    data object Unknown : GetConnectionHealthOutcome

    data object Forbidden : GetConnectionHealthOutcome
}

/** The permission port of the health read; the adapter asks `AuthorityReplica.decide` with [permissionKey]. */
fun interface ConnectionHealthViewPermission {
    fun allows(
        caller: PersonRef,
        permissionKey: String,
    ): Boolean
}

private fun healthOf(
    ports: HealthPorts,
    settings: HealthSettings,
    clock: Clock,
    connectionRef: String,
): ConnectionHealth {
    val now = clock.instant()
    return ConnectionHealth.compute(
        connectionRef,
        ports.calls.between(connectionRef, now.minus(settings.window), now),
        ports.calls.lastFailure(connectionRef),
        now,
        settings,
    )
}

/** INV-REQ-836: health of one connection. A query: it publishes nothing and writes nothing. */
class GetConnectionHealth(
    private val tx: TransactionRunner,
    private val ports: HealthPorts,
    private val permission: ConnectionHealthViewPermission,
    private val settings: HealthSettings,
    private val clock: Clock,
) {
    fun handle(
        caller: PersonRef,
        connectionRef: String,
    ): GetConnectionHealthOutcome =
        tx.inTransaction {
            if (!permission.allows(caller, PERMISSION_KEY)) return@inTransaction GetConnectionHealthOutcome.Forbidden
            if (ports.store.find(connectionRef) == null) return@inTransaction GetConnectionHealthOutcome.Unknown
            GetConnectionHealthOutcome.Found(healthOf(ports, settings, clock, connectionRef))
        }

    companion object {
        /** The `x-operation` permission of `getConnectionHealth` in platform-contracts masterdata/openapi.yaml. */
        const val PERMISSION_KEY = "connection-health.view"
    }
}

/**
 * INV-REQ-836 behavior 4: called by the adapter recording path after each recorded call (wired by INV-T-18776). The
 * first check of a connection sets the baseline and requests nothing; a later check with a different state requests
 * one [ConnectionStatusChanged]. An adapter that has not yet checked (no recorded call) requests nothing (INV-Q-3718).
 * The only code that calls [ConnectionStatusPublisher.publish] and [ConnectionStateMemory.remember].
 */
class EvaluateConnectionStatus(
    private val tx: TransactionRunner,
    private val ports: HealthPorts,
    private val memory: ConnectionStateMemory,
    private val publisher: ConnectionStatusPublisher,
    private val settings: HealthSettings,
    private val clock: Clock,
) {
    fun handle(connectionRef: String) {
        tx.inTransaction {
            if (ports.store.find(connectionRef) != null && ports.calls.hasAny(connectionRef)) {
                val health = healthOf(ports, settings, clock, connectionRef)
                val recorded = memory.lastRequested(connectionRef)
                if (recorded != health.state) {
                    if (recorded != null) {
                        publisher.publish(ConnectionStatusChanged(connectionRef, health.state, health.lastFailure))
                    }
                    memory.remember(connectionRef, health.state)
                }
            }
        }
    }
}
