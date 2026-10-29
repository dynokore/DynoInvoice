package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.binding.InstallationBindingKey
import com.dynokore.platform.tenancykernel.LaneGate
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import javax.sql.DataSource

/**
 * The one composition of the scope the event-log adapters use (INV-T-12071, follow-on of INV-T-4271; INV-AC-12070).
 * A service that needs an event-log write of its own calls [of]; service-runtime's own beans call it too.
 */
object EventLogScopes {
    /**
     * Two paths:
     *  - [eventLog] is null (no separate event-log instance): returns [scope] itself, the application scope, whose pool
     *    already holds the lane gate (INV-Q-5243); the event-log append then joins the application transaction
     *    (INV-Q-4850).
     *  - [eventLog] is the separate event-log pool: returns a [TenantScope] with the tenants, workload principal and
     *    platform of [scope] and its own [TransactionRunnerImpl] (an event-log transaction never joins an application
     *    one) over the pool handed off behind [gate], signed with [bindingKey]; the one runner is both runner slots.
     *
     * @throws IllegalArgumentException when [eventLog] is given and [bindingKey] is null (refused at construction).
     */
    @JvmStatic
    fun of(
        eventLog: DataSource?,
        scope: TenantScope,
        gate: LaneGate?,
        bindingKey: InstallationBindingKey?,
    ): TenantScope {
        if (eventLog == null) return scope
        val transactions =
            TransactionRunnerImpl(OutboxAndQueueWiring.handOff(eventLog, gate), scope.tenants, null, bindingKey?.source)
        return TenantScope(scope.tenants, transactions, transactions, scope.workloadPrincipal, scope.platform)
    }
}
