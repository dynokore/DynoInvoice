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
 *
 * The binding carries the actor with the tenant ([currentActor]; null for the
 * deprecated actor-less forms, INV-Q-3997).
 *
 * Also the [PlatformTenantBinding]: [bind] refuses [TenantId.PLATFORM], and only
 * [bindPlatform] binds it (INV-Q-2927). The composition root exposes this object
 * as a [PlatformTenantBinding] only to platform work's adapters.
 */
class TenantContextImpl :
    TenantContext,
    PlatformTenantBinding {
    /** One binding: the tenant and the actor bound with it (null for the deprecated actor-less form). */
    private data class Binding(
        val tenant: TenantId,
        val actor: String?,
    )

    private val current = ThreadLocal<Binding>()

    override fun current(): TenantId = bindingOrThrow().tenant

    override fun currentActor(): String? = bindingOrThrow().actor

    override fun <T> bind(
        tenant: TenantId,
        actor: String,
        block: () -> T,
    ): T {
        requireNotPlatform(tenant)
        return bound(Binding(tenant, requireActor(actor)), block)
    }

    @Deprecated(
        message = "Binds no actor; use bind(tenant, actor, block) (INV-Q-3997; INV-T-3999 removes this form).",
        replaceWith = ReplaceWith("bind(tenant, actor, block)"),
    )
    override fun <T> bind(
        tenant: TenantId,
        block: () -> T,
    ): T {
        requireNotPlatform(tenant)
        return bound(Binding(tenant, null), block)
    }

    override fun <T> bindPlatform(
        actor: String,
        block: () -> T,
    ): T = bound(Binding(TenantId.PLATFORM, requireActor(actor)), block)

    @Deprecated(
        message = "Binds no actor; use bindPlatform(actor, block) (INV-Q-3997; INV-T-3999 removes this form).",
        replaceWith = ReplaceWith("bindPlatform(actor, block)"),
    )
    override fun <T> bindPlatform(block: () -> T): T = bound(Binding(TenantId.PLATFORM, null), block)

    private fun bindingOrThrow(): Binding =
        current.get()
            ?: throw IllegalStateException(
                "No tenant is bound on this thread (ARC-21): the inbound adapter must call " +
                    "TenantContext.bind before any application code runs.",
            )

    private fun requireNotPlatform(tenant: TenantId) =
        require(!tenant.isPlatform) {
            "The reserved platform tenant is bound only through PlatformTenantBinding (INV-Q-2927)."
        }

    private fun requireActor(actor: String): String {
        require(actor.isNotBlank()) {
            "The actor must be the end user's sub or the service's workload principal, never blank (INV-REQ-854 b1)."
        }
        return actor
    }

    private fun <T> bound(
        binding: Binding,
        block: () -> T,
    ): T {
        val previous = current.get()
        current.set(binding)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}
