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
     * Runs [block] with [tenant] bound as [current] for its duration, restoring
     * whatever was bound before (or nothing) when it returns or throws.
     *
     * For the one caller ARC-21 names: a cross-tenant worker binding once per
     * claimed item, not a general-purpose override. Nesting restores the
     * enclosing tenant, so a worker may safely call other tenant-scoped code
     * without corrupting its caller's binding.
     */
    fun <T> bind(
        tenant: TenantId,
        block: () -> T,
    ): T
}
