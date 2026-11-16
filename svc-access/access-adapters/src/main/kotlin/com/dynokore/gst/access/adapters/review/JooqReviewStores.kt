package com.dynokore.gst.access.adapters.review

import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.REVIEW_CAMPAIGN
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.REVIEW_CAMPAIGN_GRANT
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.REVIEW_OUTCOME
import com.dynokore.gst.access.adapters.persistence.jooq.tables.references.ROLE_GRANT
import com.dynokore.gst.access.application.review.ReviewCampaigns
import com.dynokore.gst.access.application.review.ReviewGrantSource
import com.dynokore.gst.access.application.review.ReviewOutcomes
import com.dynokore.gst.access.domain.grant.GrantRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.review.Cadence
import com.dynokore.gst.access.domain.review.PeriodLabel
import com.dynokore.gst.access.domain.review.ReviewCampaign
import com.dynokore.gst.access.domain.review.ReviewDecision
import com.dynokore.gst.access.domain.review.ReviewOutcome
import com.dynokore.gst.access.domain.review.ReviewedGrant
import com.dynokore.platform.outboxandqueue.UuidV7
import org.jooq.DSLContext
import org.jooq.Record
import java.time.Clock
import java.time.ZoneOffset

/**
 * TPL-11. The campaigns of the bound tenant: the listeners of the DSLContext add the tenant to every statement and the
 * forced row-level security stays behind them. A campaign is read with `FOR UPDATE` by its reference, so two outcomes
 * of one campaign run one after the other and only one of them sees the campaign complete.
 */
class JooqReviewCampaigns(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : ReviewCampaigns {
    override fun insert(campaign: ReviewCampaign) {
        dsl
            .insertInto(REVIEW_CAMPAIGN)
            .set(REVIEW_CAMPAIGN.ID, ids.next(clock.instant()))
            .set(REVIEW_CAMPAIGN.CAMPAIGN_REF, campaign.ref)
            .set(REVIEW_CAMPAIGN.PERIOD, campaign.period.text)
            .set(REVIEW_CAMPAIGN.CADENCE, campaign.cadence.name)
            .set(REVIEW_CAMPAIGN.OPENED_BY, campaign.openedBy.value)
            .set(REVIEW_CAMPAIGN.OPENED_AT, campaign.openedAt.atOffset(ZoneOffset.UTC))
            .execute()
        campaign.grants.forEach { listed ->
            dsl
                .insertInto(REVIEW_CAMPAIGN_GRANT)
                .set(REVIEW_CAMPAIGN_GRANT.ID, ids.next(clock.instant()))
                .set(REVIEW_CAMPAIGN_GRANT.CAMPAIGN_REF, campaign.ref)
                .set(REVIEW_CAMPAIGN_GRANT.GRANT_REF, listed.grant.value)
                .set(REVIEW_CAMPAIGN_GRANT.HOLDER_REF, listed.holder.value)
                .execute()
        }
    }

    override fun find(ref: String): ReviewCampaign? =
        dsl
            .selectFrom(REVIEW_CAMPAIGN)
            .where(REVIEW_CAMPAIGN.CAMPAIGN_REF.eq(ref))
            .forUpdate()
            .fetchOne()
            ?.let { toCampaign(it) }

    override fun findByPeriod(period: PeriodLabel): ReviewCampaign? =
        dsl
            .selectFrom(REVIEW_CAMPAIGN)
            .where(REVIEW_CAMPAIGN.PERIOD.eq(period.text))
            .fetchOne()
            ?.let { toCampaign(it) }

    override fun nextRef(): String =
        "REV-" +
            ids
                .next(clock.instant())
                .toString()
                .replace("-", "")
                .takeLast(REF_DIGITS)

    private fun toCampaign(row: Record): ReviewCampaign {
        val ref = checkNotNull(row.get(REVIEW_CAMPAIGN.CAMPAIGN_REF)) { "campaign_ref is not null" }
        val period = checkNotNull(row.get(REVIEW_CAMPAIGN.PERIOD)) { "period is not null" }
        val grants =
            dsl
                .selectFrom(REVIEW_CAMPAIGN_GRANT)
                .where(REVIEW_CAMPAIGN_GRANT.CAMPAIGN_REF.eq(ref))
                .orderBy(REVIEW_CAMPAIGN_GRANT.GRANT_REF)
                .fetch()
                .map {
                    ReviewedGrant(
                        GrantRef(checkNotNull(it.get(REVIEW_CAMPAIGN_GRANT.GRANT_REF)) { "grant_ref is not null" }),
                        IdentityRef(
                            checkNotNull(it.get(REVIEW_CAMPAIGN_GRANT.HOLDER_REF)) { "holder_ref is not null" },
                        ),
                    )
                }
        return ReviewCampaign(
            ref,
            checkNotNull(PeriodLabel.parse(period)) { "the stored period '$period' is not a period label" },
            Cadence.valueOf(checkNotNull(row.get(REVIEW_CAMPAIGN.CADENCE)) { "cadence is not null" }),
            grants,
            IdentityRef(checkNotNull(row.get(REVIEW_CAMPAIGN.OPENED_BY)) { "opened_by is not null" }),
            checkNotNull(row.get(REVIEW_CAMPAIGN.OPENED_AT)) { "opened_at is not null" }.toInstant(),
        )
    }

    private companion object {
        const val REF_DIGITS = 12
    }
}

/** TPL-11. The outcomes of the bound tenant; the unique key (tenant, campaign, grant) refuses a second row. */
class JooqReviewOutcomes(
    private val dsl: DSLContext,
    private val clock: Clock,
    private val ids: UuidV7 = UuidV7(),
) : ReviewOutcomes {
    override fun find(
        campaign: String,
        grant: GrantRef,
    ): ReviewOutcome? =
        dsl
            .selectFrom(REVIEW_OUTCOME)
            .where(REVIEW_OUTCOME.CAMPAIGN_REF.eq(campaign))
            .and(REVIEW_OUTCOME.GRANT_REF.eq(grant.value))
            .fetchOne()
            ?.let { toOutcome(it) }

    override fun insert(outcome: ReviewOutcome) {
        dsl
            .insertInto(REVIEW_OUTCOME)
            .set(REVIEW_OUTCOME.ID, ids.next(clock.instant()))
            .set(REVIEW_OUTCOME.CAMPAIGN_REF, outcome.campaign)
            .set(REVIEW_OUTCOME.GRANT_REF, outcome.grant.value)
            .set(REVIEW_OUTCOME.DECISION, outcome.decision.name)
            .set(REVIEW_OUTCOME.ACTOR_REF, outcome.actor.value)
            .set(REVIEW_OUTCOME.DECIDED_AT, outcome.at.atOffset(ZoneOffset.UTC))
            .set(REVIEW_OUTCOME.ACK_REF, outcome.ackRef)
            .execute()
    }

    override fun recorded(campaign: String): List<ReviewOutcome> =
        dsl
            .selectFrom(REVIEW_OUTCOME)
            .where(REVIEW_OUTCOME.CAMPAIGN_REF.eq(campaign))
            .orderBy(REVIEW_OUTCOME.GRANT_REF)
            .fetch()
            .map { toOutcome(it) }

    private fun toOutcome(row: Record) =
        ReviewOutcome(
            checkNotNull(row.get(REVIEW_OUTCOME.CAMPAIGN_REF)) { "campaign_ref is not null" },
            GrantRef(checkNotNull(row.get(REVIEW_OUTCOME.GRANT_REF)) { "grant_ref is not null" }),
            ReviewDecision.valueOf(checkNotNull(row.get(REVIEW_OUTCOME.DECISION)) { "decision is not null" }),
            IdentityRef(checkNotNull(row.get(REVIEW_OUTCOME.ACTOR_REF)) { "actor_ref is not null" }),
            checkNotNull(row.get(REVIEW_OUTCOME.DECIDED_AT)) { "decided_at is not null" }.toInstant(),
            row.get(REVIEW_OUTCOME.ACK_REF),
        )
}

/** Every grant of the bound tenant as it is now (any status), with the person who holds it. */
class JooqReviewGrantSource(
    private val dsl: DSLContext,
) : ReviewGrantSource {
    override fun grantsOfTenant(): List<ReviewedGrant> =
        dsl
            .select(ROLE_GRANT.GRANT_REF, ROLE_GRANT.IDENTITY_REF)
            .from(ROLE_GRANT)
            .orderBy(ROLE_GRANT.GRANT_REF)
            .fetch()
            .map {
                ReviewedGrant(
                    GrantRef(checkNotNull(it.get(ROLE_GRANT.GRANT_REF)) { "grant_ref is not null" }),
                    IdentityRef(checkNotNull(it.get(ROLE_GRANT.IDENTITY_REF)) { "identity_ref is not null" }),
                )
            }
}
