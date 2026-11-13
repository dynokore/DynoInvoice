package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.events.JacksonPayloadReadyDataReader
import com.dynokore.gst.submission.application.arrival.ArrivalPlanning
import com.dynokore.gst.submission.application.arrival.CodeRaising
import com.dynokore.gst.submission.application.queue.InFlightStore
import com.dynokore.gst.submission.application.queue.PayloadReadyDataReader
import com.dynokore.gst.submission.application.queue.PayloadReadyHandler
import com.dynokore.gst.submission.application.queue.SubmissionQueue
import com.dynokore.gst.submission.application.queue.SubmissionStore
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcReplayDelivery
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.ReplayDelivery
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The consumer of `PayloadReady` (INV-REQ-816, INV-Q-7266 A step (1)). The service declares the subscription, the
 * handler and the rebuild sink and builds the one `JdbcInboxConsumer` from them (the way `OutboxAndQueueWiring`
 * builds a default one); service-runtime's event loop polls it. The library's inbox key is (consumer, event
 * id); the (docUuid, version) check is the primary key of `submission` inside the handler.
 */
@Configuration(proxyBeanMethods = false)
class ConsumerConfiguration {
    @Bean
    fun payloadReadySubscription(): ConsumerSubscription =
        ConsumerSubscription(CONSUMER, SubmissionEvents.PAYLOAD_READY_TOPIC, "submission")

    @Bean
    fun payloadReadyDataReader(): PayloadReadyDataReader = JacksonPayloadReadyDataReader()

    @Bean
    fun payloadReadyHandler(
        tenants: TenantContext,
        store: SubmissionStore,
        queue: SubmissionQueue,
        reader: PayloadReadyDataReader,
        inFlight: InFlightStore,
        planning: ArrivalPlanning,
        raising: CodeRaising,
    ): PayloadReadyHandler = PayloadReadyHandler(tenants, store, queue, reader, inFlight, planning, raising)

    /**
     * The service declares its own consumer: product-runtime also supplies `EventHandler` beans, so the default
     * single-consumer wiring of `OutboxAndQueueWiring` (one `EventHandler` by type) would be ambiguous and backs off.
     * The service has a second consumer, of `BreakGlassSessionChanged` ([BreakGlassFeed]), so the replay delivery is
     * declared below with a qualifier.
     */
    @Bean
    @Suppress("LongParameterList")
    fun payloadReadyConsumer(
        subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("payloadReadyHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            subscription,
            catalogue,
            reader,
            scope,
            TenancyJooq.dslContext(OutboxSchema.jooqTables(subscription.schema), scope.tenants, scope.transactions),
            handler,
            rebuildRequired,
            clock,
        )

    /** The replay delivery of the library, built with the parameters of `OutboxAndQueueWiring.replayDelivery`. */
    @Bean
    fun submissionReplayDelivery(
        queue: WorkQueue,
        scope: TenantScope,
        @Qualifier("payloadReadyConsumer") inbox: JdbcInboxConsumer,
    ): ReplayDelivery = JdbcReplayDelivery(queue, scope, inbox)

    @Bean
    fun rebuildRequiredSink(): RebuildRequiredSink =
        RebuildRequiredSink {
            LoggerFactory
                .getLogger(ConsumerConfiguration::class.java)
                .error(
                    "consumer {} must be rebuilt: topic {} next offset {} is at or below the watermark {}",
                    it.consumer,
                    it.topic,
                    it.nextOffset,
                    it.watermark,
                )
        }

    private companion object {
        const val CONSUMER = "submission-svc"
    }
}
