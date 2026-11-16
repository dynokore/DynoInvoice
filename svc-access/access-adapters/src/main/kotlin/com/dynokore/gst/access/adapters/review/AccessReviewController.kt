package com.dynokore.gst.access.adapters.review

import com.dynokore.gst.access.adapters.elevation.CommandKey
import com.dynokore.gst.access.adapters.elevation.ElevationCommands
import com.dynokore.gst.access.adapters.elevation.ElevationCommands.Reply
import com.dynokore.gst.access.adapters.http.ApiConflict
import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.application.review.CloseAccessReviewOutcome
import com.dynokore.gst.access.application.review.CloseAccessReviewUseCase
import com.dynokore.gst.access.application.review.ReviewStores
import com.dynokore.gst.access.application.review.RunAccessReviewOutcome
import com.dynokore.gst.access.application.review.RunAccessReviewUseCase
import com.dynokore.gst.access.contract.api.AccessReviewApi
import com.dynokore.gst.access.contract.model.AccessReviewRequest
import com.dynokore.gst.access.contract.model.AccessReviewResponse
import com.dynokore.gst.access.contract.model.CloseAccessReviewRequest
import com.dynokore.gst.access.contract.model.CloseAccessReviewRequestOutcomesInner
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.review.CampaignStatus
import com.dynokore.gst.access.domain.review.ReviewCampaign
import com.dynokore.gst.access.domain.review.ReviewDecision
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * TPL-08. `runAccessReview` and `closeAccessReview` (INV-FLOW-253, INV-REQ-894, INV-REQ-1266). The caller needs the key
 * `access-review.run` in the bound tenant ([ReviewPermissionCheck]), never a role name; the second reviewer of a
 * grant is any other holder of the key, assigned by nobody. The cadence is VP-05's: no request field sets it. Each
 * command and its Idempotency-Key record commit in one transaction (API-15); a refusal rolls back every outcome of the
 * request.
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin in the
// template)
open class AccessReviewController(
    private val run: RunAccessReviewUseCase,
    private val close: CloseAccessReviewUseCase,
    private val stores: ReviewStores,
    private val permission: ReviewPermissionCheck,
    private val commands: ElevationCommands,
    private val tenants: TenantContext,
) : AccessReviewApi {
    override fun runAccessReview(
        idempotencyKey: UUID,
        accessReviewRequest: AccessReviewRequest,
    ): ResponseEntity<AccessReviewResponse> {
        val actor = caller()
        // a campaign is opened for the bound tenant only
        if (accessReviewRequest.tenantRef !=
            tenants.current().toString()
        ) {
            throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        }
        val id = CommandKey(actor, idempotencyKey, "POST runAccessReview")
        return commands.once(id, accessReviewRequest, commands.reader(AccessReviewResponse::class.java)) {
            val campaign =
                when (val outcome = run.handle(actor, accessReviewRequest.quarter)) {
                    is RunAccessReviewOutcome.Opened -> outcome.campaign
                    is RunAccessReviewOutcome.Existing -> outcome.campaign
                    else -> throw refusalOf(outcome)
                }
            Reply(HttpStatus.CREATED, body(campaign, statusOf(campaign)))
        }
    }

    private fun refusalOf(outcome: RunAccessReviewOutcome): ApiRefusal =
        when (outcome) {
            RunAccessReviewOutcome.InvalidPeriod -> {
                ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "quarter must match [0-9]{4}Q[1-4]")
            }

            RunAccessReviewOutcome.MonthlyUnavailable -> {
                ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "monthly reviews are not available yet")
            }

            RunAccessReviewOutcome.CadenceMismatch -> {
                ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "the period does not match the tenant's cadence")
            }

            RunAccessReviewOutcome.CadenceNotReadable -> {
                ApiRefusal(ClosedErrorCode.SERVICE_NOT_READY, "the review cadence cannot be read")
            }

            is RunAccessReviewOutcome.Opened, is RunAccessReviewOutcome.Existing -> {
                error("an answered campaign is not a refusal")
            }
        }

    override fun closeAccessReview(
        reviewRef: String,
        idempotencyKey: UUID,
        closeAccessReviewRequest: CloseAccessReviewRequest,
    ): ResponseEntity<AccessReviewResponse> {
        val actor = caller()
        val id = CommandKey(actor, idempotencyKey, "POST closeAccessReview $reviewRef")
        return commands.once(id, closeAccessReviewRequest, commands.reader(AccessReviewResponse::class.java)) {
            closeAccessReviewRequest.outcomes.forEach { record(actor, reviewRef, it) }
            val campaign = stores.campaigns.find(reviewRef) ?: throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
            Reply(HttpStatus.OK, body(campaign, statusOf(campaign)))
        }
    }

    private fun record(
        actor: IdentityRef,
        reviewRef: String,
        item: CloseAccessReviewRequestOutcomesInner,
    ) {
        val decision =
            when (item.outcome) {
                CloseAccessReviewRequestOutcomesInner.Outcome.CONFIRMED -> ReviewDecision.KEEP
                CloseAccessReviewRequestOutcomesInner.Outcome.REVOKED -> ReviewDecision.REVOKE
            }
        val outcome = close.handle(actor, reviewRef, GrantRef(item.grantRef), decision)
        if (outcome is CloseAccessReviewOutcome.Conflict) {
            throw ApiConflict("the grant ${item.grantRef} already has a different outcome")
        }
        refusalOf(outcome)?.let { throw it }
    }

    private fun refusalOf(outcome: CloseAccessReviewOutcome): ApiRefusal? =
        when (outcome) {
            is CloseAccessReviewOutcome.Recorded, is CloseAccessReviewOutcome.Repeated -> {
                null
            }

            CloseAccessReviewOutcome.Conflict -> {
                null
            }

            CloseAccessReviewOutcome.OwnGrantRefused -> {
                ApiRefusal(
                    ClosedErrorCode.ACCESS_DENIED,
                    "a reviewer cannot review their own grant without the tenant's sole-operator acknowledgement",
                )
            }

            CloseAccessReviewOutcome.CampaignNotFound, CloseAccessReviewOutcome.GrantNotInCampaign -> {
                ApiRefusal(ClosedErrorCode.NOT_FOUND)
            }
        }

    private fun caller(): IdentityRef {
        val actor = tenants.currentActor() ?: throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        if (!permission.mayRunReviews()) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        return IdentityRef(actor)
    }

    private fun statusOf(campaign: ReviewCampaign): CampaignStatus =
        campaign.statusWith(
            stores.outcomes
                .recorded(campaign.ref)
                .map { it.grant }
                .toSet(),
        )

    private fun body(
        campaign: ReviewCampaign,
        status: CampaignStatus,
    ) = AccessReviewResponse(
        reviewRef = campaign.ref,
        tenantRef = tenants.current().toString(),
        quarter = campaign.period.text,
        status =
            when (status) {
                CampaignStatus.OPEN -> AccessReviewResponse.Status.OPEN
                CampaignStatus.CLOSED -> AccessReviewResponse.Status.CLOSED
            },
    )
}
