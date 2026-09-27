package com.dynokore.platform.serviceruntime.resilience

/**
 * One [ResilientEdge] per synchronous internal (§2.5) edge, each carrying its
 * own §36.6 policy (FR-AH.8, STK-24) — the one registry a service's
 * `adapters.clients` layer asks for its edge's policy by name (TPL-07), so a
 * second, independently-configured client for the same edge (ARC-08's own
 * anti-pattern) never gets the chance to exist. Edge names are
 * `<caller>-><callee>`, matching the contract's own `x-sync-edge` extension
 * (API-08) — for example `"submission-svc->transport-svc"`.
 */
fun interface ResilienceRegistry {
    fun forEdge(edge: String): ResilientEdge
}

/**
 * Runs one call under its edge's full §36.6 policy: a timeout, jittered retry
 * (only where the edge is configured as idempotent, CS-16), a circuit breaker
 * and a bulkhead (INV-REQ-1204) — with [deadline] propagated to the callee
 * (API-29, INV-REQ-1203) and, once the *policy* rather than the deadline itself
 * gives up, one of three typed fallbacks returned instead of an exception
 * (TPL-07).
 *
 * The three fallbacks map resilience4j's own outcomes, never a caller's
 * business outcome (CS-14, CS-15): [onCallNotPermitted] — the breaker is open or
 * the bulkhead is full, nothing left this service to try; [onConnectFailure] —
 * the request was refused before it was written, definitely not applied;
 * [onTimeout] — no answer arrived in time, including the deadline's own budget
 * already being exhausted before the call was even attempted (INV-REQ-1203
 * behaviour #3) — it may or may not have reached the callee either way. Any
 * other exception the block throws propagates unchanged to the caller, which
 * maps it at its own boundary (CS-14).
 */
interface ResilientEdge {
    fun <T> execute(
        deadline: Deadline,
        onCallNotPermitted: () -> T,
        onConnectFailure: () -> T,
        onTimeout: () -> T,
        block: () -> T,
    ): T
}
