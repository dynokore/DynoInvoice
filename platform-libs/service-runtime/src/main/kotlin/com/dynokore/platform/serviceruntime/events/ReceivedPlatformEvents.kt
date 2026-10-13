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
 * contract's (INV-REQ-836 b4: up, degraded, down), else [ReceivedConnectionState.UNKNOWN]: the schema leaves
 * `state` an open string a minor version may extend (INV-REQ-1163, API-09), and a consumer shows a value it does
 * not know as UNKNOWN (owner ruling INV-Q-3718).
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
    val state: ReceivedConnectionState get() = ReceivedConnectionState.of(stateText)
}

/**
 * A connection state as a consumer holds it (INV-API-3308): the contract's up, degraded and down (INV-REQ-836 b4),
 * plus [UNKNOWN] for a received state outside them. Declared in severity order, so the worst state is the
 * greatest: [UNKNOWN] ranks between [DEGRADED] and [DOWN] (owner ruling INV-Q-3718), so an unknown instance shows
 * the connection as UNKNOWN unless another instance is down. UNKNOWN is a consumer's reading only; no producer
 * publishes it ([ConnectionState] has no such value).
 */
enum class ReceivedConnectionState {
    UP,
    DEGRADED,
    UNKNOWN,
    DOWN,
    ;

    companion object {
        /** The contract state [ConnectionState] maps to. */
        fun of(state: ConnectionState): ReceivedConnectionState =
            when (state) {
                ConnectionState.UP -> UP
                ConnectionState.DEGRADED -> DEGRADED
                ConnectionState.DOWN -> DOWN
            }

        /** The received payload `state`: a contract value, else [UNKNOWN]. */
        fun of(wire: String): ReceivedConnectionState {
            val known = ConnectionState.entries.firstOrNull { it.wire == wire } ?: return UNKNOWN
            return of(known)
        }
    }
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
 * state across its instances (down, then unknown, then degraded, then up; INV-Q-3718). No order is promised across
 * instances.
 * Deduplication on the event id is the consumer's inbox; applying the same report twice changes nothing anyway.
 */
class ConnectionStatusBoard {
    /** A connection as consumers show it: its producer service and name. */
    data class ConnectionKey(
        val service: String,
        val connection: String,
    )

    private data class InstanceState(
        val state: ReceivedConnectionState,
        val time: Instant,
    )

    private val states = HashMap<ConnectionKey, HashMap<String, InstanceState>>()

    /**
     * Applies one instance's [state] at [time]; returns true when it replaced that instance's state. An older
     * report than the one held for the instance is ignored.
     */
    @Synchronized
    fun apply(
        key: ConnectionKey,
        instance: String,
        state: ReceivedConnectionState,
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
    fun worst(key: ConnectionKey): ReceivedConnectionState? = states[key]?.values?.maxOfOrNull { it.state }

    /** The state each instance last reported for the connection. */
    @Synchronized
    fun instances(key: ConnectionKey): Map<String, ReceivedConnectionState> {
        val held = states[key].orEmpty()
        return held.mapValues { it.value.state }
    }
}
