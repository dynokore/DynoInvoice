package com.dynokore.platform.serviceruntime.tracing

import com.dynokore.platform.serviceruntime.logging.TraceParent

/**
 * The accepted inbound `traceparent` of the request a call runs on behalf of. ScopedValue (JEP 506), as
 * `CurrentDeadline` and `CurrentEndUserToken`.
 */
object CurrentTraceParent {
    internal val key: ScopedValue<TraceParent> = ScopedValue.newInstance()

    fun current(): TraceParent? = if (key.isBound) key.get() else null

    fun <T> callWith(
        parent: TraceParent,
        block: () -> T,
    ): T = ScopedValue.where(key, parent).call<T, RuntimeException>(block)
}
