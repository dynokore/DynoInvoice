package com.dynokore.gst.access.application.review

import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.review.PeriodLabel
import com.dynokore.gst.access.domain.review.ReviewCampaign
import com.dynokore.gst.access.domain.review.ReviewOutcome
import com.dynokore.gst.access.domain.review.ReviewedGrant

/** The availability of a variation point as the platform reports it. */
enum class VpAvailability { PLANNED, AVAILABLE }

/** VP-05 (INV-REQ-1266): whether the monthly cadence can be used yet. Read live. */
fun interface Vp05Availability {
    fun current(): VpAvailability
}

/** The grants of the bound tenant, as they are now: the source of a campaign's grant set. */
fun interface ReviewGrantSource {
    fun grantsOfTenant(): List<ReviewedGrant>
}

interface ReviewCampaigns {
    fun insert(campaign: ReviewCampaign)

    fun find(ref: String): ReviewCampaign?

    /** The campaign of the bound tenant for [period], whatever its status; other tenants' campaigns never appear. */
    fun findByPeriod(period: PeriodLabel): ReviewCampaign?

    /** A fresh opaque campaign reference. */
    fun nextRef(): String
}

interface ReviewOutcomes {
    fun find(
        campaign: String,
        grant: GrantRef,
    ): ReviewOutcome?

    fun insert(outcome: ReviewOutcome)

    /** Every outcome recorded for the campaign. */
    fun recorded(campaign: String): List<ReviewOutcome>
}

/** What the tenant's stored cadence reads as (VP-05): QUARTERLY also when none is set; UNREADABLE fails closed. */
enum class CadenceAnswer { QUARTERLY, MONTHLY, UNREADABLE }

/** The tenant's stored review cadence. Never consulted while VP-05 is planned. */
fun interface ReviewCadenceSource {
    fun current(): CadenceAnswer
}

/**
 * Revokes [grants] when the campaign closes (INV-REQ-894 behaviour 3). Called once, in the transaction of the outcome
 * that closes the campaign, and only with a non-empty set. An exception rolls the outcome back.
 */
fun interface ReviewRevocations {
    fun revoke(
        campaign: String,
        grants: Set<GrantRef>,
        actor: IdentityRef,
    )
}

/** An audit record of the review; [actor] is a user ID, never a role. */
data class ReviewAuditEntry(
    val actor: String,
    val action: String,
    val campaign: String,
    val grant: String?,
    /** The sole-operator acknowledgement the action relied on, else null. */
    val ackRef: String?,
)

/** Writes the audit entry in the command transaction. */
fun interface ReviewAudit {
    fun record(entry: ReviewAuditEntry)
}
