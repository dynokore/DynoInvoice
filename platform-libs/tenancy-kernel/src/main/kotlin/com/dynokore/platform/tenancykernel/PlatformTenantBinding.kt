package com.dynokore.platform.tenancykernel

/**
 * The only way to bind [TenantId.PLATFORM] (Keel INV-Q-2927 option D, INV-Q-3016).
 *
 * A separate port from [TenantContext] so that the composition root hands it only to
 * platform work's adapters: the job runner, operator commands, installation-level
 * `setPolicy`, platform-wide pack readiness, and the outbox relay and consumer that
 * carry platform rows. An adapter that derives its tenant from a token or a request
 * never receives it. Binding nests and restores exactly like [TenantContext.bind].
 *
 * Framework-free (ARC-16): this is the `-api` half of the module.
 */
interface PlatformTenantBinding {
    /** Runs [block] with [TenantId.PLATFORM] bound as [TenantContext.current], restoring the prior binding after. */
    fun <T> bindPlatform(block: () -> T): T
}
