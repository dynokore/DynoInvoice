package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.events.JacksonAcknowledgementOutcomeReader
import com.dynokore.gst.submission.adapters.events.JacksonAcknowledgementReceivedDataReader
import com.dynokore.gst.submission.adapters.events.JacksonRejectedAcknowledgementReader
import com.dynokore.gst.submission.adapters.events.SubmissionAcknowledgedOutbox
import com.dynokore.gst.submission.adapters.persistence.JooqAcknowledgementRecorder
import com.dynokore.gst.submission.adapters.persistence.JooqLatestAttempt
import com.dynokore.gst.submission.application.ordering.AcknowledgedHook
import com.dynokore.gst.submission.application.queue.AcknowledgedPublisher
import com.dynokore.gst.submission.application.queue.AcknowledgementDataReader
import com.dynokore.gst.submission.application.queue.AcknowledgementOutcomeReader
import com.dynokore.gst.submission.application.queue.AcknowledgementOutcomeRouter
import com.dynokore.gst.submission.application.queue.AcknowledgementReceivedHandler
import com.dynokore.gst.submission.application.queue.AcknowledgementRecorder
import com.dynokore.gst.submission.application.queue.ErrorCatalogue
import com.dynokore.gst.submission.application.queue.FailureCodes
import com.dynokore.gst.submission.application.queue.FailureSignals
import com.dynokore.gst.submission.application.queue.InFlightStore
import com.dynokore.gst.submission.application.queue.LatestAttempt
import com.dynokore.gst.submission.application.queue.RejectedAcknowledgementHandler
import com.dynokore.gst.submission.application.queue.RejectedAcknowledgementReader
import com.dynokore.gst.submission.application.queue.SubmissionStore
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The consumer of `AcknowledgementReceived` (INV-REQ-797, INV-AC-24271): the recorder, the reader, the
 * `SubmissionAcknowledged` outbox publisher, the handler and one `JdbcInboxConsumer`, following
 * [AccessPointRouteConsumerConfiguration]. The handler runs in the consumer's transaction, so no transaction wrapper
 * is added here; it locks through the existing [InFlightStore] and [SubmissionStore] beans. The consumer refuses to
 * be built when the catalogue does not list the event on exactly its topic (fail closed).
 */
@Configuration(proxyBeanMethods = false)
class AcknowledgementConsumerConfiguration {
    @Bean
    fun acknowledgementRecorder(dsl: DSLContext): AcknowledgementRecorder = JooqAcknowledgementRecorder(dsl)

    @Bean
    fun acknowledgementDataReader(): AcknowledgementDataReader = JacksonAcknowledgementReceivedDataReader()

    @Bean
    fun acknowledgedPublisher(
        outbox: OutboxWriter,
        tenants: TenantContext,
        store: SubmissionStore,
    ): AcknowledgedPublisher = SubmissionAcknowledgedOutbox(outbox, tenants, store)

    @Bean
    @Suppress("LongParameterList") // composition root: the handler takes eight collaborators
    fun acknowledgementReceivedHandler(
        tenants: TenantContext,
        reader: AcknowledgementDataReader,
        inFlight: InFlightStore,
        store: SubmissionStore,
        recorder: AcknowledgementRecorder,
        publisher: AcknowledgedPublisher,
        clock: Clock,
        heldRelease: AcknowledgedHook,
    ): AcknowledgementReceivedHandler =
        AcknowledgementReceivedHandler(tenants, reader, inFlight, store, recorder, publisher, clock, heldRelease)

    @Bean
    fun acknowledgementOutcomeReader(): AcknowledgementOutcomeReader = JacksonAcknowledgementOutcomeReader()

    @Bean
    fun rejectedAcknowledgementReader(): RejectedAcknowledgementReader = JacksonRejectedAcknowledgementReader()

    @Bean
    fun latestAttempt(dsl: DSLContext): LatestAttempt = JooqLatestAttempt(dsl)

    @Bean
    @Suppress("LongParameterList") // composition root: the handler takes eight collaborators
    fun rejectedAcknowledgementHandler(
        tenants: TenantContext,
        reader: RejectedAcknowledgementReader,
        inFlight: InFlightStore,
        store: SubmissionStore,
        attempts: LatestAttempt,
        codes: FailureCodes,
        catalogue: ErrorCatalogue,
        signals: FailureSignals,
    ): RejectedAcknowledgementHandler =
        RejectedAcknowledgementHandler(tenants, reader, inFlight, store, attempts, codes, catalogue, signals)

    /** The consumer's handler: routes by outcome; ACCEPTED and absent reach the acknowledgement handler. */
    @Bean
    fun acknowledgementOutcomeRouter(
        reader: AcknowledgementOutcomeReader,
        // the runtime wraps every EventHandler bean in a tracing decorator, so both are taken as EventHandler by name
        @Qualifier("acknowledgementReceivedHandler") accepted: EventHandler,
        @Qualifier("rejectedAcknowledgementHandler") rejected: EventHandler,
    ): AcknowledgementOutcomeRouter = AcknowledgementOutcomeRouter(reader, accepted, rejected)

    @Bean
    @Suppress("LongParameterList")
    fun acknowledgementReceivedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("acknowledgementOutcomeRouter") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer {
        val subscription = subscription()
        val entry = catalogue.find(SubmissionEvents.ACKNOWLEDGEMENT_RECEIVED_TYPE)
        check(entry != null && entry.topic == subscription.topic) {
            "the event catalogue does not list ${SubmissionEvents.ACKNOWLEDGEMENT_RECEIVED_TYPE} on " +
                SubmissionEvents.ACKNOWLEDGEMENT_RECEIVED_TOPIC
        }
        return JdbcInboxConsumer(
            subscription,
            catalogue,
            reader,
            scope,
            TenancyJooq.dslContext(OutboxSchema.jooqTables(subscription.schema), scope.tenants, scope.transactions),
            handler,
            rebuildRequired,
            clock,
        )
    }

    companion object {
        /** Not a bean (see [ExtensionConsumerConfiguration.subscription]). */
        fun subscription(): ConsumerSubscription =
            ConsumerSubscription(
                "submission-svc-acknowledgement-received",
                SubmissionEvents.ACKNOWLEDGEMENT_RECEIVED_TOPIC,
                "submission",
            )
    }
}
