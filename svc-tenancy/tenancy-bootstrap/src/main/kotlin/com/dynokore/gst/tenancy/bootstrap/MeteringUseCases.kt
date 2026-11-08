package com.dynokore.gst.tenancy.bootstrap

import com.dynokore.gst.tenancy.adapters.events.ArchiveVolumeHandler
import com.dynokore.gst.tenancy.adapters.events.SubmissionAcknowledgedHandler
import com.dynokore.gst.tenancy.adapters.events.TenancyEventCatalogue
import com.dynokore.gst.tenancy.adapters.persistence.JooqMeteringStore
import com.dynokore.gst.tenancy.application.metering.Metering
import com.dynokore.gst.tenancy.application.metering.MeteringStore
import com.dynokore.gst.tenancy.application.metering.PlanLimitPublisher
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * TPL-18: metering, plan limits and the three consumers that feed them (INV-REQ-878, INV-REQ-879), constructed here
 * and nowhere else. Each consumer is an inbox consumer of its own, so a repeated envelope id counts once.
 */
@Configuration(proxyBeanMethods = false)
class MeteringUseCases {
    @Bean
    fun meteringStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): MeteringStore = JooqMeteringStore(dsl, tenants)

    @Bean
    fun metering(
        tx: TransactionRunner,
        store: MeteringStore,
        publisher: ObjectProvider<PlanLimitPublisher>,
        clock: ObjectProvider<Clock>,
    ): Metering = Metering(tx, store, publisher.getIfAvailable()) { clock.getIfAvailable(Clock::systemUTC).instant() }

    @Bean
    fun submissionAcknowledgedConsumer(
        metering: Metering,
        deps: ConsumerDependencies,
    ): InboxConsumer =
        deps.consumer(
            "tenancy-svc-submission-acknowledged",
            TenancyEventCatalogue.SUBMISSION_ACKNOWLEDGED_TOPIC,
            SubmissionAcknowledgedHandler(metering),
        )

    @Bean
    fun archiveVolumeMeasuredConsumer(
        metering: Metering,
        deps: ConsumerDependencies,
    ): InboxConsumer =
        deps.consumer(
            "tenancy-svc-archive-volume-measured",
            TenancyEventCatalogue.ARCHIVE_VOLUME_TOPIC,
            ArchiveVolumeHandler(metering, TenancyEventCatalogue.ARCHIVE_VOLUME_TYPE, perEntity = true),
        )

    @Bean
    fun tenantWideArchiveVolumeMeasuredConsumer(
        metering: Metering,
        deps: ConsumerDependencies,
    ): InboxConsumer =
        deps.consumer(
            "tenancy-svc-tenant-wide-archive-volume-measured",
            TenancyEventCatalogue.TENANT_WIDE_ARCHIVE_VOLUME_TOPIC,
            ArchiveVolumeHandler(metering, TenancyEventCatalogue.TENANT_WIDE_ARCHIVE_VOLUME_TYPE, perEntity = false),
        )
}
