package com.dynokore.gst.transport.application

import com.dynokore.gst.transport.domain.TenantRef
import java.time.Clock
import java.time.Instant

/** Class of a connection test (INV-REQ-835); names fixed by the transport contract task INV-T-18670. */
enum class TestClass { SUCCESS, UNREACHABLE, AUTHENTICATION_REFUSED, UNEXPECTED_RESPONSE }

/** Health state of a connection, equal to the circuit-breaker state after the outcome (INV-REQ-836). */
enum class ConnectionState { UP, DEGRADED, DOWN }

/** Circuit-breaker state of the adapter (INV-REQ-1353): closed is UP, half-open DEGRADED, open DOWN. */
enum class BreakerState(
    val state: ConnectionState,
) {
    CLOSED(ConnectionState.UP),
    HALF_OPEN(ConnectionState.DEGRADED),
    OPEN(ConnectionState.DOWN),
}

/** What the AP probe saw; [detail] is a code only, never free text (INV-Q-2944), and absent on SUCCESS. */
data class ProbeOutcome(
    val classification: TestClass,
    val detail: String? = null,
)

/** Port of the AP probe: one call to the access point through the connection's adapter. */
fun interface ApProbe {
    fun probe(
        tenant: TenantRef,
        connectionRef: String,
    ): ProbeOutcome
}

/** Port of the adapter circuit breaker; thresholds live behind it, not in this module. */
fun interface AdapterBreaker {
    /** Records the outcome and returns the breaker state after it. */
    fun record(
        connectionRef: String,
        success: Boolean,
    ): BreakerState
}

/** Port of the stored status of a connection. */
interface ConnectionStatusStore {
    fun stored(
        tenant: TenantRef,
        connectionRef: String,
    ): ConnectionState?

    fun save(
        tenant: TenantRef,
        connectionRef: String,
        state: ConnectionState,
    )
}

/** Port that requests the `ConnectionStatusChanged` event. */
fun interface ConnectionStatusEvents {
    fun requestChanged(
        tenant: TenantRef,
        connectionRef: String,
        state: ConnectionState,
        lastFailureReason: String?,
    )
}

/** The probe returned a detail that is not a code (`^[A-Z][A-Z0-9_-]{1,31}$`), or a detail on SUCCESS. */
class InvalidProbeDetail(
    reason: String,
) : IllegalStateException(reason)

data class ConnectionTestResult(
    val connectionRef: String,
    val classification: TestClass,
    val detail: String?,
    val state: ConnectionState,
    val checkedAt: Instant,
)

/**
 * `testConnection` (INV-REQ-835, INV-REQ-836 behavior 4): probes the AP, records the outcome in the breaker and
 * returns the class with `state` equal to the breaker state after the outcome. The state never comes from the class
 * alone. `ConnectionStatusChanged` is requested only where the state differs from the stored one.
 */
class TestConnection(
    private val probe: ApProbe,
    private val breaker: AdapterBreaker,
    private val status: ConnectionStatusStore,
    private val events: ConnectionStatusEvents,
    private val clock: Clock,
) {
    fun test(
        tenant: TenantRef,
        connectionRef: String,
    ): ConnectionTestResult {
        val outcome = probe.probe(tenant, connectionRef)
        validate(outcome)
        val success = outcome.classification == TestClass.SUCCESS
        val state = breaker.record(connectionRef, success).state
        if (status.stored(tenant, connectionRef) != state) {
            status.save(tenant, connectionRef, state)
            events.requestChanged(tenant, connectionRef, state, outcome.detail.takeUnless { success })
        }
        return ConnectionTestResult(connectionRef, outcome.classification, outcome.detail, state, clock.instant())
    }

    private fun validate(outcome: ProbeOutcome) {
        val detail = outcome.detail
        if (outcome.classification == TestClass.SUCCESS) {
            if (detail != null) throw InvalidProbeDetail("a SUCCESS result carries no detail")
        } else if (detail != null && !CODE.matches(detail)) {
            throw InvalidProbeDetail("detail is not a code")
        }
    }

    private companion object {
        val CODE = Regex("^[A-Z][A-Z0-9_-]{1,31}$")
    }
}
