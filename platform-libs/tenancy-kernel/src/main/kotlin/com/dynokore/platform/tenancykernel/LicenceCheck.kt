package com.dynokore.platform.tenancykernel

/**
 * The entitlement/licence-status port (SDS §12.1).
 *
 * FR-G.12 / the tenancy-svc `must_not` row: this check **never blocks
 * submission or any other request path**, on an entitlement limit or an
 * expired licence alike. That is a contract on every implementation of this
 * port, not just an aspiration — [NonBlockingLicenceCheck] is the kernel's own
 * decorator that enforces it structurally regardless of what a given
 * implementation does internally (a slow HTTP call, a corrupt licence file):
 * bound by a timeout, [LicenceStatus.UNKNOWN] on timeout or any exception,
 * never a throw. A caller wires the real check (SaaS entitlement source, or a
 * private installation's signed licence file) underneath that decorator, never
 * this interface directly, so the guarantee cannot be bypassed by a new
 * implementation forgetting to make it (product-runtime/bootstrap wiring,
 * TPL-18; out of this module's scope).
 *
 * Declared here, in the `-api` half, so `application` code can depend on the
 * port without depending on how entitlement is actually sourced (ARC-16).
 */
fun interface LicenceCheck {
    fun status(tenant: TenantId): LicenceStatus
}
