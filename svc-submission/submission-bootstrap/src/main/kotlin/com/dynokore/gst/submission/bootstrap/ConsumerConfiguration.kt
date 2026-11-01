package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.events.JacksonPayloadReadyDataReader
import com.dynokore.gst.submission.application.queue.PayloadReadyDataReader
import com.dynokore.gst.submission.application.queue.PayloadReadyHandler
import com.dynokore.gst.submission.application.queue.SubmissionQueue
import com.dynokore.gst.submission.application.queue.SubmissionStore
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import org.slf4j.LoggerFactory
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
    ): PayloadReadyHandler = PayloadReadyHandler(tenants, store, queue, reader)

    /**
     * The service declares its own consumer: product-runtime also supplies `EventHandler` beans, so the default
     * single-consumer wiring of `OutboxAndQueueWiring` (one `EventHandler` by type) would be ambiguous and backs off.
     */
    @Bean
    @Suppress("LongParameterList")
    fun payloadReadyConsumer(
        subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        handler: PayloadReadyHandler,
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
