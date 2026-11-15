package com.dynokore.gst.access.application.review

import com.dynokore.gst.access.domain.grant.GrantRef
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

    /** A fresh opaque campaign reference. */
    fun nextRef(): String
}

interface ReviewOutcomes {
    fun find(
        campaign: String,
        grant: GrantRef,
    ): ReviewOutcome?

    fun insert(outcome: ReviewOutcome)

    /** The grants of the campaign that have an outcome. */
    fun recordedGrants(campaign: String): Set<GrantRef>
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
