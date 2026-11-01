package com.dynokore.gst.transport.adapters.persistence

import com.dynokore.gst.transport.application.AttemptOwnerRef
import com.dynokore.gst.transport.application.NotificationUnitOfWork
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.util.UUID

/**
 * The unit of work of an AP notification (INV-REQ-585): binds the tenant of the owner it is given, whatever tenant the
 * caller has bound, and runs the block in one transaction, so the ledger row and the event commit together or not at
 * all (INV-AC-623).
 */
class JooqNotificationUnitOfWork(
    private val scope: TenantScope,
    private val runner: TransactionRunner,
    private val workloadPrincipal: String,
) : NotificationUnitOfWork {
    override fun <T> run(
        owner: AttemptOwnerRef,
        block: () -> T,
    ): T =
        scope.bind(TenantId(UUID.fromString(owner.tenant.value)), workloadPrincipal) {
            runner.inTransaction { block() }
        }
}
