package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.http.LiveTenantGrants
import com.dynokore.gst.access.adapters.review.CatalogueVp05Availability
import com.dynokore.gst.access.adapters.review.GrantAuditReview
import com.dynokore.gst.access.adapters.review.GrantRevokingReviewRevocations
import com.dynokore.gst.access.adapters.review.JooqReviewCampaigns
import com.dynokore.gst.access.adapters.review.JooqReviewGrantSource
import com.dynokore.gst.access.adapters.review.JooqReviewOutcomes
import com.dynokore.gst.access.adapters.review.LiveReviewPermissionCheck
import com.dynokore.gst.access.adapters.review.ReviewPermissionCheck
import com.dynokore.gst.access.adapters.review.VariationPointReviewCadence
import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.RevokeGrantUseCase
import com.dynokore.gst.access.application.review.CloseAccessReviewUseCase
import com.dynokore.gst.access.application.review.ReviewAudit
import com.dynokore.gst.access.application.review.ReviewCampaigns
import com.dynokore.gst.access.application.review.ReviewOutcomes
import com.dynokore.gst.access.application.review.ReviewRevocations
import com.dynokore.gst.access.application.review.ReviewSources
import com.dynokore.gst.access.application.review.ReviewStores
import com.dynokore.gst.access.application.review.RunAccessReviewUseCase
import com.dynokore.gst.access.application.separation.SoleOperatorAcks
import com.dynokore.gst.access.domain.role.PermissionMatrix
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.configresolver.VariationPointCatalogue
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** TPL-18: the access-review use cases (INV-T-15798, INV-REQ-894, INV-REQ-1266) and their adapters. */
@Configuration(proxyBeanMethods = false)
class ReviewUseCases {
    @Bean
    fun reviewCampaigns(
        dsl: DSLContext,
        clock: Clock,
    ): ReviewCampaigns = JooqReviewCampaigns(dsl, clock)

    @Bean
    fun reviewOutcomes(
        dsl: DSLContext,
        clock: Clock,
    ): ReviewOutcomes = JooqReviewOutcomes(dsl, clock)

    @Bean
    fun reviewStores(
        campaigns: ReviewCampaigns,
        outcomes: ReviewOutcomes,
    ) = ReviewStores(campaigns, outcomes)

    @Bean
    fun reviewAudit(audit: GrantAudit): ReviewAudit = GrantAuditReview(audit)

    @Bean
    fun reviewSources(
        catalogue: VariationPointCatalogue,
        reader: PolicyReader,
        tenants: TenantContext,
        dsl: DSLContext,
        clock: Clock,
    ) = ReviewSources(
        CatalogueVp05Availability(catalogue),
        VariationPointReviewCadence(reader, tenants, clock),
        JooqReviewGrantSource(dsl),
    )

    @Bean
    fun reviewRevocations(revoke: RevokeGrantUseCase): ReviewRevocations = GrantRevokingReviewRevocations(revoke)

    @Bean
    fun runAccessReviewUseCase(
        tx: TransactionRunner,
        sources: ReviewSources,
        campaigns: ReviewCampaigns,
        audit: ReviewAudit,
        clock: Clock,
    ) = RunAccessReviewUseCase(tx, sources, campaigns, audit, clock)

    @Bean
    @Suppress("LongParameterList") // one parameter per collaborator of the use case (TPL-18: the one place it is built)
    fun closeAccessReviewUseCase(
        tx: TransactionRunner,
        stores: ReviewStores,
        acks: SoleOperatorAcks,
        revocations: ReviewRevocations,
        audit: ReviewAudit,
        clock: Clock,
    ) = CloseAccessReviewUseCase(tx, stores, acks, revocations, audit, clock)

    /** The key `access-review.run` decided from the caller's live grants and this service's matrix, per request. */
    @Bean
    fun reviewPermissionCheck(
        tenants: TenantContext,
        live: LiveTenantGrants,
        matrix: PermissionMatrix,
    ): ReviewPermissionCheck = LiveReviewPermissionCheck(tenants, live, matrix)
}
