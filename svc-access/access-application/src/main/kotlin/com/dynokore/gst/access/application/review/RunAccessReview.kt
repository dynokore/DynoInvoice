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

    /** The tenant already has a campaign for the period (OPEN or CLOSED): it is answered, nothing is stored. */
    data class Existing(
        val campaign: ReviewCampaign,
    ) : RunAccessReviewOutcome

    /** The label is not a quarter or month label: no campaign is stored. */
    data object InvalidPeriod : RunAccessReviewOutcome

    /** A monthly label while VP-05 is planned: no campaign is stored. */
    data object MonthlyUnavailable : RunAccessReviewOutcome

    /** VP-05 is not planned and the label does not match the tenant's stored cadence. */
    data object CadenceMismatch : RunAccessReviewOutcome

    /** VP-05 is not planned and the stored cadence cannot be read: nothing is opened (fail closed). */
    data object CadenceNotReadable : RunAccessReviewOutcome
}

/**
 * `runAccessReview` (INV-FLOW-253, INV-REQ-894, INV-REQ-1266): opens a campaign for a period label holding every grant
 * of the tenant at opening. The cadence is VP-05's, never taken from the label: while VP-05 is planned it is
 * quarterly and only a quarter label opens; otherwise the label must match the stored cadence. A repeat for the same
 * period answers the existing campaign. The caller's permission `access-review.run` is decided by the caller.
 */
class RunAccessReviewUseCase(
    private val tx: TransactionRunner,
    private val sources: ReviewSources,
    private val campaigns: ReviewCampaigns,
    private val audit: ReviewAudit,
    private val clock: Clock,
) {
    fun handle(
        actor: IdentityRef,
        periodLabel: String,
    ): RunAccessReviewOutcome {
        val period = PeriodLabel.parse(periodLabel) ?: return RunAccessReviewOutcome.InvalidPeriod
        return tx.inTransaction {
            val existing = campaigns.findByPeriod(period)
            if (existing != null) {
                RunAccessReviewOutcome.Existing(existing)
            } else {
                openFor(actor, period)
            }
        }
    }

    private fun openFor(
        actor: IdentityRef,
        period: PeriodLabel,
    ): RunAccessReviewOutcome {
        val planned = sources.availability.current() == VpAvailability.PLANNED
        val answer = if (planned) CadenceAnswer.QUARTERLY else sources.cadences.current()
        return when {
            planned && period is PeriodLabel.Month -> RunAccessReviewOutcome.MonthlyUnavailable
            answer == CadenceAnswer.UNREADABLE -> RunAccessReviewOutcome.CadenceNotReadable
            answer == CadenceAnswer.QUARTERLY && period is PeriodLabel.Quarter -> open(actor, period, Cadence.QUARTERLY)
            answer == CadenceAnswer.MONTHLY && period is PeriodLabel.Month -> open(actor, period, Cadence.MONTHLY)
            else -> RunAccessReviewOutcome.CadenceMismatch
        }
    }

    private fun open(
        actor: IdentityRef,
        period: PeriodLabel,
        cadence: Cadence,
    ): RunAccessReviewOutcome {
        val campaign =
            ReviewCampaign(
                campaigns.nextRef(),
                period,
                cadence,
                sources.grants.grantsOfTenant(),
                actor,
                clock.instant(),
            )
        campaigns.insert(campaign)
        audit.record(ReviewAuditEntry(actor.value, "runAccessReview", campaign.ref, null, null))
        return RunAccessReviewOutcome.Opened(campaign)
    }
}
