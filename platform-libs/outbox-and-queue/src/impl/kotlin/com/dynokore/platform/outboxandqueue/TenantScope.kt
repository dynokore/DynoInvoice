package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner

/**
 * The three tenancy-kernel pieces the relay and the consumer work through: the [TenantContext]
 * they bind per claimed item or record (ARC-21), the [TransactionRunner] that sets the tenant and the actor as
 * the first statement of every transaction, and the [CurrentConnection] of that transaction.
 * Rows under the reserved platform tenant are bound through the [PlatformTenantBinding] (INV-Q-2927).
 * The composition root builds one from the service's `TransactionRunnerImpl`, and one over the event-log
 * DataSource for the event binding and the reader.
 *
 * Every binding carries an actor (INV-REQ-854 b1, ARC-21; coordinator rulings INV-Q-3997, INV-Q-4268): [bind] takes
 * the actor its caller holds (an end user's `sub` for request or operator work), and [bindWorkload] binds
 * [workloadPrincipal], the service's workload principal the caller's wiring supplies, for background work on a
 * claimed item (relay, inbox, replay, dead-letter triage, event-log reads and publishes).
 */
class TenantScope
    @JvmOverloads
    constructor(
        val tenants: TenantContext,
        val transactions: TransactionRunner,
        val connections: CurrentConnection,
        val workloadPrincipal: String,
        val platform: PlatformTenantBinding? = tenants as? PlatformTenantBinding,
    ) {
        init {
            require(workloadPrincipal.isNotBlank()) { "the workload principal must not be blank (INV-REQ-854 b1)" }
        }

        /**
         * Binds [tenant] and [actor] for [block]: the reserved `TenantId.PLATFORM` of a platform row or a tenantless
         * platform event through [platform] (INV-Q-2927 option D), any other tenant through [tenants].
         *
         * @throws IllegalStateException when [tenant] is the platform tenant and no [platform] binding was given.
         * @throws IllegalArgumentException when [actor] is blank.
         */
        fun <T> bind(
            tenant: TenantId,
            actor: String,
            block: () -> T,
        ): T =
            if (tenant.isPlatform) {
                checkNotNull(
                    platform,
                ) { "platform rows need a PlatformTenantBinding in this TenantScope" }.bindPlatform(actor, block)
            } else {
                tenants.bind(tenant, actor, block)
            }

        /** [bind] with the service's [workloadPrincipal]: background work only (INV-REQ-854 b1). */
        fun <T> bindWorkload(
            tenant: TenantId,
            block: () -> T,
        ): T = bind(tenant, workloadPrincipal, block)
    }
