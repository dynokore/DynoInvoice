package com.dynokore.platform.serviceruntime.resilience.http

import com.dynokore.platform.serviceruntime.resilience.Deadline

/**
 * The ambient [Deadline] a call is running under, so the generated RestClient's
 * own interceptor — deep inside code this module never sees — can read it and
 * set `X-Deadline-Ms` (API-29), with no explicit parameter threading through
 * generated client code. `ScopedValue`, never `ThreadLocal` (JEP 506, S2-28):
 * bound fresh on the same virtual thread that then runs the call, both at a
 * request's own entry ([DeadlineServerFilter]) and around a `ResilientEdge`'s
 * own dispatch onto a fresh virtual thread
 * (`Resilience4jResilientEdge`, whose own binding is what a call submitted onto
 * a different thread than the filter's actually observes).
 */
object CurrentDeadline {
    internal val key: ScopedValue<Deadline> = ScopedValue.newInstance()

    /** The deadline bound around the currently-running call, if any. */
    fun current(): Deadline? = if (key.isBound) key.get() else null

    /** Binds [deadline] for [block]'s duration, on whichever thread calls this. */
    fun <T> callWith(
        deadline: Deadline,
        block: () -> T,
    ): T = ScopedValue.where(key, deadline).call<T, RuntimeException>(block)
}

/**
 * The propagated end-user token (INV-REQ-1202 behaviour #2): the raw
 * `Authorization` header of the inbound request this call is being made on
 * behalf of, captured by [EndUserTokenCaptureFilter] and forwarded by
 * [EndUserTokenPropagationInterceptor]. Absent for a call with no inbound
 * request behind it at all (a scheduled job) — every internal call still
 * carries its own [CurrentDeadline] and workload token regardless.
 */
object CurrentEndUserToken {
    internal val key: ScopedValue<String> = ScopedValue.newInstance()

    fun current(): String? = if (key.isBound) key.get() else null

    fun <T> callWith(
        token: String,
        block: () -> T,
    ): T = ScopedValue.where(key, token).call<T, RuntimeException>(block)
}
