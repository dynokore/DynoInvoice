package com.dynokore.platform.tenancykernel

/**
 * The tenant already bound for the current request or message (ARC-21).
 *
 * The inbound adapter binds it exactly once, at the edge — from the verified
 * token on an HTTP request, from the `tenantid` extension on an inbound event,
 * or per claimed item in a job (ARC §4.3 step 1a) — before any application or
 * domain code runs. Application code reads it with [current]; it never binds
 * its own tenant except the one cross-tenant-worker case [bind] exists for.
 *
 * Framework-free (ARC-16): this is the `-api` half of the module, the only
 * half another module or a service's `application` layer may depend on.
 */
interface TenantContext {
    /**
     * The tenant bound by the inbound adapter for this request or message.
     *
     * @throws IllegalStateException if nothing is bound on this thread — a
     *   defect in the calling adapter (ARC-21), not a recoverable condition.
     */
    fun current(): TenantId

    /**
     * The actor bound together with [current] by [bind] with an actor or
     * [PlatformTenantBinding.bindPlatform] with an actor: the end user's `sub`, or the
     * service's workload principal for background work (INV-REQ-854 b1). Null when the
     * binding was made through the actor-less form, which leaves `app.actor` unset
     * (coordinator ruling INV-Q-3997).
     *
     * @throws IllegalStateException if nothing is bound on this thread, as [current].
     */
    fun currentActor(): String?

    /**
     * Runs [block] with [tenant] and [actor] bound as [current] and [currentActor] for its
     * duration, restoring whatever was bound before (or nothing) when it returns or throws.
     *
     * Called only by the inbound adapter that holds the principal (the verified token's
     * tenant claim and `sub`) or the claimed item (its tenant, with the service's workload
     * principal as the actor), so the tenant [TransactionRunner] binds always comes from
     * one of them (ARC-21; owner rulings INV-Q-3951, INV-Q-3953; INV-Q-3997).
     *
     * @throws IllegalArgumentException if [actor] is blank, or if [tenant] is
     *   [TenantId.PLATFORM] (bound only through [PlatformTenantBinding], INV-Q-2927).
     */
    fun <T> bind(
        tenant: TenantId,
        actor: String,
        block: () -> T,
    ): T

    /**
     * Runs [block] with [tenant] bound as [current] for its duration, restoring
     * whatever was bound before (or nothing) when it returns or throws.
     *
     * For the one caller ARC-21 names: a cross-tenant worker binding once per
     * claimed item, not a general-purpose override. Nesting restores the
     * enclosing tenant, so a worker may safely call other tenant-scoped code
     * without corrupting its caller's binding.
     *
     * Binds no actor ([currentActor] is null, `app.actor` stays unset). Deprecated in favour
     * of the actor-bound `bind(tenant, actor, block)`; the removal task deletes this form
     * (coordinator ruling INV-Q-3997).
     *
     * @throws IllegalArgumentException if [tenant] is [TenantId.PLATFORM]: the reserved
     *   platform tenant is bound only through [PlatformTenantBinding] (INV-Q-2927).
     */
    @Deprecated(
        message = "Binds no actor; use bind(tenant, actor, block) (INV-Q-3997; INV-T-3999 removes this form).",
        replaceWith = ReplaceWith("bind(tenant, actor, block)"),
    )
    fun <T> bind(
        tenant: TenantId,
        block: () -> T,
    ): T
}
