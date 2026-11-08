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

/** The stores a health read uses. */
data class HealthPorts(
    val store: ConnectionStore,
    val calls: CallRecords,
    val memory: ConnectionStateMemory,
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

/**
 * INV-REQ-836: health of one connection. Requests one [ConnectionStatusChanged] when the state differs from the one
 * last requested, and none when it is unchanged or the adapter has not yet checked (INV-Q-3718).
 */
class GetConnectionHealth(
    private val tx: TransactionRunner,
    private val ports: HealthPorts,
    private val publisher: ConnectionStatusPublisher,
    private val permission: ConnectionViewPermission,
    private val settings: HealthSettings,
    private val clock: Clock,
) {
    fun handle(
        caller: PersonRef,
        connectionRef: String,
    ): GetConnectionHealthOutcome =
        tx.inTransaction {
            if (!permission.allows(caller)) return@inTransaction GetConnectionHealthOutcome.Forbidden
            if (ports.store.find(connectionRef) == null) return@inTransaction GetConnectionHealthOutcome.Unknown
            val now = clock.instant()
            val health =
                ConnectionHealth.compute(
                    connectionRef,
                    ports.calls.between(connectionRef, now.minus(settings.window), now),
                    ports.calls.lastFailure(connectionRef),
                    now,
                    settings,
                )
            if (ports.calls.hasAny(connectionRef) && ports.memory.lastRequested(connectionRef) != health.state) {
                publisher.publish(ConnectionStatusChanged(connectionRef, health.state, health.lastFailure))
                ports.memory.remember(connectionRef, health.state)
            }
            GetConnectionHealthOutcome.Found(health)
        }
}
