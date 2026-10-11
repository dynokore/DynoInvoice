package com.dynokore.platform.serviceruntime.connection

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.binding.SecretRef
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant

/**
 * The uniform connection record (INV-REQ-834, INV-NOTE-3 §35.13): endpoint, credential reference in the
 * secret store (never the secret, INV-BR-1845), environment tag and owner service, one per external
 * service and environment. [tenant] and [entity] say whose connection it is: an installation connection
 * (the reference registries) is held under the reserved platform tenant and entity (INV-REQ-1226 b7).
 */
data class ConnectionRecord(
    val name: String,
    val endpoint: String,
    val credential: SecretRef,
    val environment: String,
    val ownerService: String,
    val tenant: TenantId = TenantId.PLATFORM,
    val entity: EntityRef = EntityRef.PLATFORM,
) {
    init {
        require(name.isNotBlank() && endpoint.isNotBlank() && environment.isNotBlank() && ownerService.isNotBlank()) {
            "a connection record has a name, endpoint, environment tag and owner service"
        }
        require(tenant.isPlatform == entity.isPlatform) { "the platform tenant and the platform entity go together" }
    }
}

/**
 * The classes of a "Test connection" result (INV-REQ-835 b3, INV-Q-2028): success; unreachable (network or
 * DNS); authentication refused; unexpected response (reached and authenticated, but not the expected contract).
 */
enum class ConnectionTestClass { SUCCESS, UNREACHABLE, AUTHENTICATION_REFUSED, UNEXPECTED_RESPONSE }

/** A classified test result: its class and an error code, never a raw error or content (CS-34, INV-Q-2944). */
data class ConnectionTestResult(val outcome: ConnectionTestClass, val errorCode: String? = null) {
    init {
        require(errorCode == null || ERROR_CODE.matches(errorCode)) {
            "a test result carries a catalogue error code only"
        }
    }

    private companion object {
        val ERROR_CODE = Regex("^[A-Z]{2,4}-[0-9]{3}$")
    }
}

/**
 * Run by the service that owns the record, against the endpoint with the referenced credential
 * (INV-REQ-835 b2, b4).
 */
fun interface ConnectionTester {
    fun test(connection: ConnectionRecord): ConnectionTestResult
}

/** The adapter's circuit-breaker state (INV-REQ-836 b4, adapter-runtime). */
enum class CircuitBreakerState { CLOSED, HALF_OPEN, OPEN }

/**
 * The connection state; [wire] is the payload's `state` (INV-REQ-836 b4: closed = up, half-open = degraded,
 * open = down).
 */
enum class ConnectionState(val wire: String) {
    UP("up"),
    DEGRADED("degraded"),
    DOWN("down"),
    ;

    companion object {
        fun of(breaker: CircuitBreakerState): ConnectionState = when (breaker) {
            CircuitBreakerState.CLOSED -> UP
            CircuitBreakerState.HALF_OPEN -> DEGRADED
            CircuitBreakerState.OPEN -> DOWN
        }
    }
}

/** The most recent failure: when, and why as an error code or exception class, never a message (CS-34). */
data class ConnectionFailure(val at: Instant, val reason: String) {
    init {
        require(reason.isNotBlank()) { "a failure reason is an error code or exception class" }
    }
}

/** A connection's health as shown to `integration.engineer` and the operations role (INV-REQ-836 b1). */
data class ConnectionHealth(
    val successRate: Double,
    val latencyMillis: Long,
    val lastFailure: ConnectionFailure?,
    val breaker: CircuitBreakerState,
) {
    init {
        require(successRate in 0.0..1.0) { "success rate is a fraction" }
        require(latencyMillis >= 0) { "latency must not be negative" }
    }

    val state: ConnectionState get() = ConnectionState.of(breaker)
}

/**
 * The connection contract's reporting side (INV-REQ-836 b3-b4, INV-REQ-835 b5): an adapter reports each
 * observation of its circuit breaker; this instance publishes `ConnectionStatusChanged` only when its own
 * state for the connection changes, with its instance id in the envelope. Consumers fold the instances of a
 * connection to the worst state (INV-Q-2983).
 */
interface ConnectionStatusReporter {
    /** @return true when the state changed and `ConnectionStatusChanged` was written. */
    fun report(
        connection: ConnectionRecord,
        breaker: CircuitBreakerState,
        lastFailure: ConnectionFailure? = null,
    ): Boolean
}
