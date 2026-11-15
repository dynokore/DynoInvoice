package com.dynokore.gst.access.application.review

import com.dynokore.gst.access.application.separation.SoleOperatorAcks
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.review.CampaignStatus
import com.dynokore.gst.access.domain.review.ReviewDecision
import com.dynokore.gst.access.domain.review.ReviewOutcome
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock

sealed interface CloseAccessReviewOutcome {
    /** Recorded now; [status] is CLOSED when this was the last missing outcome. */
    data class Recorded(
        val status: CampaignStatus,
    ) : CloseAccessReviewOutcome

    /** The same outcome again: nothing changed. */
    data class Repeated(
        val status: CampaignStatus,
    ) : CloseAccessReviewOutcome

    /** A different outcome for a grant that has one (409). */
    data object Conflict : CloseAccessReviewOutcome

    /** The caller's own grant and no ACTIVE sole-operator acknowledgement (403 SRT-004). */
    data object OwnGrantRefused : CloseAccessReviewOutcome

    data object CampaignNotFound : CloseAccessReviewOutcome

    data object GrantNotInCampaign : CloseAccessReviewOutcome
}

/**
 * `closeAccessReview` (INV-FLOW-253, INV-REQ-894): records one outcome per grant with the acting user ID and the time.
 * A reviewer cannot review their own grants unless the tenant's sole-operator acknowledgement is ACTIVE (INV-REQ-994);
 * then the audit entry names it. Any other holder of `access-review.run` may review them: nobody assigns. The campaign
 * is CLOSED when every grant has an outcome. The caller's permission `access-review.run` is decided by the caller of
 * this use case.
 */
class CloseAccessReviewUseCase(
    private val tx: TransactionRunner,
    private val campaigns: ReviewCampaigns,
    private val outcomes: ReviewOutcomes,
    private val acks: SoleOperatorAcks,
    private val revocations: ReviewRevocations,
    private val audit: ReviewAudit,
    private val clock: Clock,
) {
    fun handle(
        actor: IdentityRef,
        campaignRef: String,
        grant: GrantRef,
        decision: ReviewDecision,
    ): CloseAccessReviewOutcome =
        tx.inTransaction {
            val campaign = campaigns.find(campaignRef) ?: return@inTransaction CloseAccessReviewOutcome.CampaignNotFound
            val holder = campaign.holderOf(grant) ?: return@inTransaction CloseAccessReviewOutcome.GrantNotInCampaign
            var ackRef: String? = null
            if (holder == actor) {
                ackRef =
                    acks.findActive()?.takeIf { it.active }?.ref
                        ?: return@inTransaction CloseAccessReviewOutcome.OwnGrantRefused
            }
            val existing = outcomes.find(campaignRef, grant)
            if (existing != null) {
                val status = campaign.statusWith(recordedGrants(campaignRef))
                return@inTransaction if (existing.decision == decision) {
                    CloseAccessReviewOutcome.Repeated(status)
                } else {
                    CloseAccessReviewOutcome.Conflict
                }
            }
            outcomes.insert(ReviewOutcome(campaignRef, grant, decision, actor, clock.instant(), ackRef))
            audit.record(ReviewAuditEntry(actor.value, "closeAccessReview", campaignRef, grant.value, ackRef))
            val status = campaign.statusWith(recordedGrants(campaignRef))
            if (status == CampaignStatus.CLOSED) {
                val revoked = outcomes.recorded(campaignRef).filter { it.decision == ReviewDecision.REVOKE }
                if (revoked.isNotEmpty()) revocations.revoke(campaignRef, revoked.map { it.grant }.toSet(), actor)
            }
            CloseAccessReviewOutcome.Recorded(status)
        }

    private fun recordedGrants(campaign: String) = outcomes.recorded(campaign).map { it.grant }.toSet()
}
