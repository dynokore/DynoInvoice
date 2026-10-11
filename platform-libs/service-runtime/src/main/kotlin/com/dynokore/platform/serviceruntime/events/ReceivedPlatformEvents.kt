package com.dynokore.platform.serviceruntime.events

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.serviceruntime.connection.ConnectionFailure
import com.dynokore.platform.serviceruntime.connection.ConnectionState
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant

/**
 * A `ConnectionStatusChanged` as a consumer reads it (INV-API-3308): one instance's state of one connection.
 * The publishing instance is the envelope's `instanceid`, the producer service its `source`; an installation
 * connection arrives without `tenantid` and reads back as the platform tenant (INV-Q-2927 option D).
 *
 * [stateText] is the payload's `state` as received. [state] is that value when it is one of the connection
 * contract's (INV-REQ-836 b4: up, degraded, down), else `null`: the schema leaves `state` an open string, and
 * how a consumer ranks a state it does not know is not decided (reported on INV-T-2875), so this type does not
 * rank it.
 */
data class ConnectionStatusReport(
    val eventId: EventId,
    val service: String,
    val instance: String,
    val tenant: TenantId,
    val entity: EntityRef,
    val connection: String,
    val stateText: String,
    val lastFailure: ConnectionFailure?,
    val time: Instant,
) {
    val state: ConnectionState? get() = ConnectionState.entries.firstOrNull { it.wire == stateText }
}

/**
 * An `EvidenceArtefactRecorded` as evidence-svc reads it (INV-API-3310): the artefact as received, an unknown
 * [EvidenceArtefact.type] kept as it is, with the producing service and tenant from the envelope.
 */
data class ReceivedEvidenceArtefact(
    val eventId: EventId,
    val service: String,
    val tenant: TenantId,
    val entity: EntityRef,
    val artefact: EvidenceArtefact,
    val time: Instant,
)

/**
 * The connection status a consumer keeps (INV-API-3308, INV-Q-2983): state per (producer service, connection,
 * instance), where within one instance's stream the latest envelope time wins, and per connection the worst
 * state across its instances (down, then degraded, then up). No order is promised across instances.
 * Deduplication on the event id is the consumer's inbox; applying the same report twice changes nothing anyway.
 */
class ConnectionStatusBoard {
    /** A connection as consumers show it: its producer service and name. */
    data class ConnectionKey(val service: String, val connection: String)

    private data class InstanceState(val state: ConnectionState, val time: Instant)

    private val states = HashMap<ConnectionKey, HashMap<String, InstanceState>>()

    /**
     * Applies one instance's [state] at [time]; returns true when it replaced that instance's state. An older
     * report than the one held for the instance is ignored.
     */
    @Synchronized
    fun apply(
        key: ConnectionKey,
        instance: String,
        state: ConnectionState,
        time: Instant,
    ): Boolean {
        val instances = states.getOrPut(key) { HashMap() }
        val held = instances[instance]
        if (held != null && !time.isAfter(held.time)) return false
        instances[instance] = InstanceState(state, time)
        return true
    }

    /** The worst state across the connection's instances, or `null` when none has reported. */
    @Synchronized
    fun worst(key: ConnectionKey): ConnectionState? {
        val reported = states[key]?.values ?: return null
        return reported.map { it.state }.maxByOrNull { it.severity() }
    }

    /** The state each instance last reported for the connection. */
    @Synchronized
    fun instances(key: ConnectionKey): Map<String, ConnectionState> = states[key].orEmpty().mapValues { it.value.state }

    private fun ConnectionState.severity(): Int = when (this) {
        ConnectionState.UP -> 0
        ConnectionState.DEGRADED -> 1
        ConnectionState.DOWN -> 2
    }
}
