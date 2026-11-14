package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqSubmissionRowSource
import com.dynokore.gst.submission.application.listing.ListSubmissions
import com.dynokore.gst.submission.application.listing.SubmissionRowSource
import com.dynokore.gst.submission.application.listing.SubmissionSummaryQuery
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** TPL-18 wiring of the submission list (INV-REQ-975, INV-AC-21818): the row source adapter and the use case. */
@Configuration(proxyBeanMethods = false)
class ListingConfiguration {
    @Bean
    fun submissionRowSource(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): SubmissionRowSource = JooqSubmissionRowSource(dsl, runner)

    @Bean
    fun listSubmissions(
        replica: AuthorityReplica,
        source: SubmissionRowSource,
    ): ListSubmissions = ListSubmissions(replica, source)

    @Bean
    fun submissionSummaryQuery(
        replica: AuthorityReplica,
        source: SubmissionRowSource,
    ): SubmissionSummaryQuery = SubmissionSummaryQuery(replica, source)
}
