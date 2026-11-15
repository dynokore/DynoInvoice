package com.dynokore.gst.submission.adapters.http

import com.dynokore.gst.submission.application.listing.GetSubmissionReconciliation
import com.dynokore.gst.submission.application.listing.SubmissionReconciliationRequest
import com.dynokore.gst.submission.application.listing.SubmissionReconciliationResult
import com.dynokore.gst.submission.contract.model.ReconciliationBucketTotal
import com.dynokore.gst.submission.contract.model.SentVersusAcknowledgedView
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import org.springframework.stereotype.Component
import java.time.Clock
import com.dynokore.gst.submission.contract.model.ReconciliationBucket as ContractBucket

/**
 * The delegate that serves `getSubmissionReconciliation` for `TransactionsController` (INV-REQ-976). The instant of the
 * classification is read here, once, before the use case, so the view and `listSubmissions` classify with the same
 * function at a supplied instant. The permission is decided inside the use case through `AuthorityReplica.decide`; this
 * class reads no role name and holds no permission string (INV-Q-10027). A refusal is thrown as [ComplianceRefusal] and
 * rendered once by `TransactionsRefusalAdvice`.
 */
@Component
class SubmissionReconciliationController(
    private val useCase: GetSubmissionReconciliation,
    private val clock: Clock,
) {
    fun reconcile(
        caller: Principal,
        entityRef: String,
        periodRef: String,
    ): SentVersusAcknowledgedView {
        val tenant = caller.tenant ?: throw ComplianceRefusal(ClosedErrorCode.UNAUTHENTICATED)
        val now = clock.instant()
        val deadline = CurrentDeadline.current() ?: error("no call deadline is bound")
        val result =
            useCase.handle(
                SubmissionReconciliationRequest(tenant, caller, entityRef, periodRef, now),
                deadline,
            )
        return when (result) {
            is SubmissionReconciliationResult.Totals -> {
                SentVersusAcknowledgedView(
                    entityRef,
                    periodRef,
                    result.totals.map {
                        ReconciliationBucketTotal(
                            ContractBucket.valueOf(it.bucket.name),
                            it.count,
                            it.totalAmount,
                        )
                    },
                )
            }

            SubmissionReconciliationResult.Forbidden -> {
                throw ComplianceRefusal(ClosedErrorCode.ACCESS_DENIED)
            }
        }
    }
}
