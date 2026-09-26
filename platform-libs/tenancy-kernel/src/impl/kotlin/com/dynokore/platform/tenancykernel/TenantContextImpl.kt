package com.dynokore.platform.tenancykernel

/**
 * [TenantContext] bound on the calling thread only (ARC-21): each request or
 * message is handled on one thread from bind to completion (the platform's
 * synchronous, virtual-thread-per-request model, STK-03), so a `ThreadLocal`
 * is exactly the tenant's lifetime — nothing to clear explicitly at the end of
 * a request, and no leakage between concurrently handled requests on
 * different threads.
 *
 * A worker thread that is reused across requests (a thread pool, not a fresh
 * virtual thread per request) MUST still call [bind] once per unit of work; it
 * always restores the prior value (or nothing) on the way out, so a forgotten
 * unbind is not a way for one tenant's context to leak into the next.
 */
class TenantContextImpl : TenantContext {
    private val current = ThreadLocal<TenantId>()

    override fun current(): TenantId = current.get()
        ?: throw IllegalStateException(
            "No tenant is bound on this thread (ARC-21): the inbound adapter must call " +
                "TenantContext.bind before any application code runs.",
        )

    override fun <T> bind(
        tenant: TenantId,
        block: () -> T,
    ): T {
        val previous = current.get()
        current.set(tenant)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
