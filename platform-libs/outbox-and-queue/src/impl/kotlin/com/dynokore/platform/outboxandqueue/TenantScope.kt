package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner

/**
 * The three tenancy-kernel pieces the relay and the consumer work through: the [TenantContext]
 * they bind per claimed item or record (ARC-21), the [TransactionRunner] that sets the tenant as
 * the first statement of every transaction, and the [CurrentConnection] of that transaction.
 * Rows under the reserved platform tenant are bound through the [PlatformTenantBinding] (INV-Q-2927).
 * The composition root builds one from the service's `TransactionRunnerImpl`.
 */
class TenantScope
@JvmOverloads
constructor(
    val tenants: TenantContext,
    val transactions: TransactionRunner,
    val connections: CurrentConnection,
    val platform: PlatformTenantBinding? = tenants as? PlatformTenantBinding,
) {
    /**
     * Binds [tenant] for [block]: the reserved `TenantId.PLATFORM` of a platform row or a tenantless
     * platform event through [platform] (INV-Q-2927 option D), any other tenant through [tenants].
     *
     * @throws IllegalStateException when [tenant] is the platform tenant and no [platform] binding was given.
     */
    fun <T> bind(
        tenant: TenantId,
        block: () -> T,
    ): T = if (tenant.isPlatform) {
        checkNotNull(platform) { "platform rows need a PlatformTenantBinding in this TenantScope" }.bindPlatform(block)
    } else {
        tenants.bind(tenant, block)
    }
}
