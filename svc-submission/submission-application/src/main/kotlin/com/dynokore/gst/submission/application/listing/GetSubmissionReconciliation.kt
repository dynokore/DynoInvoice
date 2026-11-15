package com.dynokore.gst.submission.application.listing

import com.dynokore.gst.submission.domain.reconciliation.BucketTotal
import com.dynokore.gst.submission.domain.reconciliation.DocFact
import com.dynokore.gst.submission.domain.reconciliation.SentVersusAcknowledged
import com.dynokore.gst.submission.domain.reconciliation.VersionFact
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant

/** The scoped key a MACHINE caller needs: `x-operation.permission` of `getSubmissionReconciliation`. */
object ReconciliationActions {
    const val READ_MACHINE = "machine.reconciliation.read"
}

/** One `getSubmissionReconciliation` call; [now] is supplied by the caller, the use case reads no clock. */
data class SubmissionReconciliationRequest(
    val tenant: TenantId,
    val principal: Principal,
    val entityRef: String,
    val periodRef: String,
    val now: Instant,
)

sealed interface SubmissionReconciliationResult {
    /** Exactly five totals in the order of `ReconciliationBucket.entries`. */
    data class Totals(
        val totals: List<BucketTotal>,
    ) : SubmissionReconciliationResult

    data object Forbidden : SubmissionReconciliationResult
}

/**
 * The sent-versus-acknowledged view (INV-REQ-976, INV-AC-22764): one [DocFact] per docUuid, classified by
 * [SentVersusAcknowledged] at the request instant, the same function and instant as the list ([documentBuckets]).
 * The key is decided per request through [AuthorityReplica.decide] (`machine.reconciliation.read` for a MACHINE,
 * `submission.view-status` otherwise); a denial reads nothing. No role name is read (INV-Q-10027).
 */
class GetSubmissionReconciliation(
    private val replica: AuthorityReplica,
    private val source: SubmissionRowSource,
) {
    fun handle(
        request: SubmissionReconciliationRequest,
        deadline: Deadline,
    ): SubmissionReconciliationResult {
        val key =
            if (request.principal.kind == PrincipalKind.MACHINE) {
                ReconciliationActions.READ_MACHINE
            } else {
                ListingActions.VIEW_STATUS
            }
        val decision =
            replica.decide(AuthorizationRequest(request.tenant, request.principal, request.entityRef, key), deadline)
        if (!decision.allowed) {
            return SubmissionReconciliationResult.Forbidden
        }
        val rows = source.rows(request.tenant, request.entityRef, request.periodRef).rows
        val docs =
            rows.groupBy { it.docUuid }.map { (docUuid, group) ->
                val ordered = group.sortedBy { it.version }
                DocFact(
                    docUuid,
                    ordered.map { VersionFact(it.version, it.state, it.acknowledgementDeadline) },
                    ordered.last().taxableAmount,
                )
            }
        return SubmissionReconciliationResult.Totals(SentVersusAcknowledged.view(docs, request.now))
    }
}
