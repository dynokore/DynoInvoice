package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.exceptions.JacksonPayloadRejectedReader
import com.dynokore.gst.consoleBff.adapters.exceptions.JacksonSubmissionFailedReader
import com.dynokore.gst.consoleBff.application.exceptions.ExceptionStore
import com.dynokore.gst.consoleBff.application.exceptions.PayloadRejectedHandler
import com.dynokore.gst.consoleBff.application.exceptions.PayloadRejectedReader
import com.dynokore.gst.consoleBff.application.exceptions.RecordDocumentHeld
import com.dynokore.gst.consoleBff.application.exceptions.SubmissionFailedHandler
import com.dynokore.gst.consoleBff.application.exceptions.SubmissionFailedReader
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The inbox consumers of `SubmissionFailed` and `PayloadRejected` (INV-AC-21058): one `JdbcInboxConsumer` per topic
 * over the handlers of INV-T-21059 and the store of INV-T-21029, in schema `console_bff`, with the consumer name of
 * [ExceptionConsumerConfiguration]. The store and the rebuild sink are the beans of that configuration.
 */
@Configuration(proxyBeanMethods = false)
class FailureConsumerConfiguration {
    @Bean
    fun submissionFailedSubscription(): ConsumerSubscription =
        ConsumerSubscription(ExceptionConsumerConfiguration.CONSUMER, SUBMISSION_FAILED_TOPIC, SCHEMA)

    @Bean
    fun payloadRejectedSubscription(): ConsumerSubscription =
        ConsumerSubscription(ExceptionConsumerConfiguration.CONSUMER, PAYLOAD_REJECTED_TOPIC, SCHEMA)

    @Bean
    fun submissionFailedReader(): SubmissionFailedReader = JacksonSubmissionFailedReader()

    @Bean
    fun payloadRejectedReader(): PayloadRejectedReader = JacksonPayloadRejectedReader()

    @Bean
    fun submissionFailedHandler(
        tenants: TenantContext,
        reader: SubmissionFailedReader,
        store: ExceptionStore,
    ): SubmissionFailedHandler = SubmissionFailedHandler(tenants, reader, RecordDocumentHeld(store))

    @Bean
    fun payloadRejectedHandler(
        tenants: TenantContext,
        reader: PayloadRejectedReader,
        store: ExceptionStore,
    ): PayloadRejectedHandler = PayloadRejectedHandler(tenants, reader, RecordDocumentHeld(store))

    @Bean
    @Suppress("LongParameterList")
    fun submissionFailedConsumer(
        @Qualifier("submissionFailedSubscription") subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("submissionFailedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer = consumer(subscription, catalogue, reader, scope, handler, rebuildRequired, clock)

    @Bean
    @Suppress("LongParameterList")
    fun payloadRejectedConsumer(
        @Qualifier("payloadRejectedSubscription") subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("payloadRejectedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer = consumer(subscription, catalogue, reader, scope, handler, rebuildRequired, clock)

    @Suppress("LongParameterList")
    private fun consumer(
        subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        handler: EventHandler,
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

    companion object {
        const val SCHEMA = ExceptionConsumerConfiguration.SCHEMA
        const val SUBMISSION_FAILED_TOPIC = "gst.submission.submission-failed.v1"
        const val PAYLOAD_REJECTED_TOPIC = "gst.payload.payload-rejected.v1"
    }
}
