package com.dynokore.gst.access.application.review

import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.review.Cadence
import com.dynokore.gst.access.domain.review.PeriodLabel
import com.dynokore.gst.access.domain.review.ReviewCampaign
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock

sealed interface RunAccessReviewOutcome {
    data class Opened(
        val campaign: ReviewCampaign,
    ) : RunAccessReviewOutcome

    /** The label is not a quarter label (and not a month label): no campaign is stored. */
    data object InvalidPeriod : RunAccessReviewOutcome

    /** A monthly label while VP-05 is planned: no campaign is stored. */
    data object MonthlyUnavailable : RunAccessReviewOutcome
}

/**
 * `runAccessReview` (INV-FLOW-253, INV-REQ-894, INV-REQ-1266): opens a campaign for a period label holding every grant
 * of the tenant at opening. The cadence is VP-05's, not a property of the request: a quarter label is quarterly, a
 * month label is refused while VP-05 is planned. The caller's permission `access-review.run` is decided by the caller
 * of this use case.
 */
class RunAccessReviewUseCase(
    private val tx: TransactionRunner,
    private val availability: Vp05Availability,
    private val grants: ReviewGrantSource,
    private val campaigns: ReviewCampaigns,
    private val audit: ReviewAudit,
    private val clock: Clock,
) {
    fun handle(
        actor: IdentityRef,
        periodLabel: String,
    ): RunAccessReviewOutcome {
        val period = PeriodLabel.parse(periodLabel)
        val cadence = cadenceFor(period)
        return when {
            period == null -> RunAccessReviewOutcome.InvalidPeriod
            cadence == null -> RunAccessReviewOutcome.MonthlyUnavailable
            else -> open(actor, period, cadence)
        }
    }

    /** The cadence a label stands for; null for a month label while VP-05 is planned (and for no label). */
    private fun cadenceFor(period: PeriodLabel?): Cadence? =
        when {
            period is PeriodLabel.Quarter -> Cadence.QUARTERLY
            period is PeriodLabel.Month && availability.current() != VpAvailability.PLANNED -> Cadence.MONTHLY
            else -> null
        }

    private fun open(
        actor: IdentityRef,
        period: PeriodLabel,
        cadence: Cadence,
    ): RunAccessReviewOutcome =
        tx.inTransaction {
            val campaign =
                ReviewCampaign(campaigns.nextRef(), period, cadence, grants.grantsOfTenant(), actor, clock.instant())
            campaigns.insert(campaign)
            audit.record(ReviewAuditEntry(actor.value, "runAccessReview", campaign.ref, null, null))
            RunAccessReviewOutcome.Opened(campaign)
        }
}
