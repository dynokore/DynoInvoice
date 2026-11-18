package com.dynokore.gst.reconciliation.adapters.http

import com.dynokore.gst.reconciliation.application.run.GetReconciliation
import com.dynokore.gst.reconciliation.contract.api.ReconciliationApi
import com.dynokore.gst.reconciliation.contract.model.Reconciliation
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The reconciliation view and variance annotation operations of the reconciliation contract (TPL-08).
 * `getReconciliation` is implemented (INV-T-24421, INV-REQ-657): 200 with the latest completed run, 503 PER-016 with
 * `Retry-After` while a return is recorded and no run has completed, 200 with PER-008 only when no return is
 * recorded. Every other operation answers 501 until the task named for it in `operations.tsv` overrides its method:
 * the generated interface's default is NOT_IMPLEMENTED.
 *
 * Who may call is decided by the generated check of the operation, not here. The read runs in one tenant
 * transaction; a port failure is never mapped to any of the three answers.
 */
@RestController
open class ReconciliationController(
    private val getReconciliation: GetReconciliation,
    private val transactions: TransactionRunner,
    private val tenants: TenantContext,
    private val retryAfter: PendingRetryAfter,
) : ReconciliationApi {
    override fun getReconciliation(
        entityRef: String,
        periodRef: String,
    ): ResponseEntity<Reconciliation> {
        val tenant = tenants.current().toString()
        val view = transactions.inTransaction { getReconciliation.execute(tenant, entityRef, periodRef) }
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
        return ReconciliationResponses.answer(view, entityRef, periodRef, retryAfter, correlation)
    }
}
