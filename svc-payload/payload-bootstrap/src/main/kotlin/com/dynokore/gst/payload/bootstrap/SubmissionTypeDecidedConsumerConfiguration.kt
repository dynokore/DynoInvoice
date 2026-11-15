package com.dynokore.gst.payload.bootstrap

import com.dynokore.gst.payload.adapters.events.SubmissionTypeDecidedHandler
import com.dynokore.gst.payload.adapters.persistence.JooqBuildInputs
import com.dynokore.gst.payload.adapters.persistence.JooqBuildJobs
import com.dynokore.gst.payload.adapters.persistence.JooqTypeDecisionStore
import com.dynokore.gst.payload.application.build.BuildTrigger
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcReplayDelivery
import com.dynokore.platform.outboxandqueue.ReplayDelivery
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The subscription of payload-svc to `SubmissionTypeDecided` and the beans of the build trigger, which the canonical
 * input consumer reuses. The consumer name stays `payload-svc`: the offset is keyed by (consumer, topic).
 */
@Configuration(proxyBeanMethods = false)
class SubmissionTypeDecidedConsumerConfiguration {
    @Bean
    fun buildJobs(
        dsl: DSLContext,
        queue: WorkQueue,
        tenants: TenantContext,
    ): JooqBuildJobs = JooqBuildJobs(dsl, queue) { tenants.current().value }

    @Bean
    fun typeDecisionStore(
        dsl: DSLContext,
        jobs: JooqBuildJobs,
        tenants: TenantContext,
    ): JooqTypeDecisionStore = JooqTypeDecisionStore(dsl, jobs) { tenants.current().value }

    @Bean
    fun buildInputs(
        dsl: DSLContext,
        tenants: TenantContext,
    ): JooqBuildInputs = JooqBuildInputs(dsl) { tenants.current().value }

    @Bean
    fun buildTrigger(
        inputs: JooqBuildInputs,
        decisions: JooqTypeDecisionStore,
        jobs: JooqBuildJobs,
    ): BuildTrigger = BuildTrigger(inputs, decisions, jobs)

    /**
     * With two inbox consumers the platform default `replayDelivery` (one consumer) is ambiguous: keep replay on the
     * version facts consumer it was bound to before this subscription existed.
     */
    @Bean
    fun replayDelivery(
        queue: WorkQueue,
        scope: TenantScope,
        @Qualifier("versionFactsConsumer") inbox: JdbcInboxConsumer,
    ): ReplayDelivery = JdbcReplayDelivery(queue, scope, inbox)

    @Bean
    @Suppress("LongParameterList")
    fun submissionTypeDecidedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        decisions: JooqTypeDecisionStore,
        trigger: BuildTrigger,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            SubmissionTypeDecidedHandler(decisions, trigger),
            rebuildRequired,
            clock,
        )

    companion object {
        const val CONSUMER = "payload-svc"
        const val TOPIC = "gst.submission.submission-type-decided.v1"
        const val TYPE = "com.dynokore.gst.submission.SubmissionTypeDecided.v1"
        const val SCHEMA = "payload"
    }
}
