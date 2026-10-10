package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner

/**
 * The three tenancy-kernel pieces the relay and the consumer work through: the [TenantContext]
 * they bind per claimed item or record (ARC-21), the [TransactionRunner] that sets the tenant as
 * the first statement of every transaction, and the [CurrentConnection] of that transaction.
 * The composition root builds one from the service's `TransactionRunnerImpl`.
 */
class TenantScope(
    val tenants: TenantContext,
    val transactions: TransactionRunner,
    val connections: CurrentConnection,
)
