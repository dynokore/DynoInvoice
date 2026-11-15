package com.dynokore.gst.access.domain.review

import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import java.time.Instant

/** How often grants are reviewed (VP-05, INV-REQ-1266): never less often than quarterly. */
enum class Cadence { QUARTERLY, MONTHLY }

/** What the reviewer decides for one grant (INV-REQ-894 behaviour 2). */
enum class ReviewDecision { KEEP, REVOKE }

enum class CampaignStatus { OPEN, CLOSED }

/** The reading of a period label: a quarter (`2026Q3`), a month (`2026-09`) or nothing valid. */
sealed interface PeriodLabel {
    val text: String

    @JvmInline
    value class Quarter(
        override val text: String,
    ) : PeriodLabel

    @JvmInline
    value class Month(
        override val text: String,
    ) : PeriodLabel

    companion object {
        private val QUARTER = Regex("[0-9]{4}Q[1-4]")
        private val MONTH = Regex("[0-9]{4}-(0[1-9]|1[0-2])")

        /** Whole-string match, case sensitive; any other text (a trailing space, `2026q3`, `2026H1`, `2026`) is null. */
        fun parse(raw: String): PeriodLabel? =
            when {
                QUARTER.matches(raw) -> Quarter(raw)
                MONTH.matches(raw) -> Month(raw)
                else -> null
            }
    }
}

/** One grant listed in a campaign and the person who holds it. */
data class ReviewedGrant(
    val grant: GrantRef,
    val holder: IdentityRef,
)

/** The outcome recorded for one grant: by whom (a user ID, never a role) and when. */
data class ReviewOutcome(
    val campaign: String,
    val grant: GrantRef,
    val decision: ReviewDecision,
    val actor: IdentityRef,
    val at: Instant,
    /** The sole-operator acknowledgement that allowed a review of the actor's own grant, else null. */
    val ackRef: String? = null,
)

/** A review campaign: the grants of the tenant at opening. It is OPEN until every grant has an outcome. */
data class ReviewCampaign(
    val ref: String,
    val period: PeriodLabel,
    val cadence: Cadence,
    val grants: List<ReviewedGrant>,
    val openedBy: IdentityRef,
    val openedAt: Instant,
) {
    init {
        require(ref.isNotBlank()) { "campaign reference is required" }
        require(grants.map { it.grant }.toSet().size == grants.size) { "a grant is listed once" }
    }

    fun holderOf(grant: GrantRef): IdentityRef? = grants.firstOrNull { it.grant == grant }?.holder

    /** CLOSED exactly when every grant has a recorded outcome. */
    fun statusWith(recorded: Set<GrantRef>): CampaignStatus =
        if (grants.all { it.grant in recorded }) CampaignStatus.CLOSED else CampaignStatus.OPEN
}
