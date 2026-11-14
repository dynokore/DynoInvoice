package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.adapters.exceptions.JacksonDocumentHeldReader
import com.dynokore.gst.consoleBff.adapters.exceptions.JacksonDocumentReleasedReader
import com.dynokore.gst.consoleBff.adapters.exceptions.JooqExceptionStore
import com.dynokore.gst.consoleBff.application.exceptions.DocumentHeldHandler
import com.dynokore.gst.consoleBff.application.exceptions.DocumentHeldReader
import com.dynokore.gst.consoleBff.application.exceptions.DocumentReleasedHandler
import com.dynokore.gst.consoleBff.application.exceptions.DocumentReleasedReader
import com.dynokore.gst.consoleBff.application.exceptions.ExceptionStore
import com.dynokore.gst.consoleBff.application.exceptions.RecordDocumentHeld
import com.dynokore.gst.consoleBff.application.exceptions.RecordDocumentReleased
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.ContractViolation
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcReplayDelivery
import com.dynokore.platform.outboxandqueue.OutboxSchema
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.ReplayDelivery
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.tenancyjooq.TenancyJooq
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The inbox consumers of `DocumentHeld` and `DocumentReleased` (INV-AC-21028): one `JdbcInboxConsumer` per topic over
 * the handlers of INV-T-21030 and the store of INV-T-21029, in schema `console_bff`. The service declares its own
 * consumers (the single-consumer default of `OutboxAndQueueWiring` backs off) and, as it has two, one qualified
 * replay delivery, as svc-submission declares its second consumer.
 */
@Configuration(proxyBeanMethods = false)
class ExceptionConsumerConfiguration {
    @Bean
    fun documentHeldSubscription(): ConsumerSubscription = ConsumerSubscription(CONSUMER, HELD_TOPIC, SCHEMA)

    @Bean
    fun documentReleasedSubscription(): ConsumerSubscription = ConsumerSubscription(CONSUMER, RELEASED_TOPIC, SCHEMA)

    @Bean
    fun exceptionStore(dsl: DSLContext): ExceptionStore = JooqExceptionStore(dsl)

    @Bean
    fun documentHeldReader(): DocumentHeldReader = JacksonDocumentHeldReader()

    @Bean
    fun documentReleasedReader(): DocumentReleasedReader = JacksonDocumentReleasedReader()

    @Bean
    fun documentHeldHandler(
        tenants: TenantContext,
        reader: DocumentHeldReader,
        store: ExceptionStore,
    ): DocumentHeldHandler = DocumentHeldHandler(tenants, reader, RecordDocumentHeld(store))

    @Bean
    fun documentReleasedHandler(
        tenants: TenantContext,
        reader: DocumentReleasedReader,
        store: ExceptionStore,
    ): DocumentReleasedHandler = DocumentReleasedHandler(tenants, reader, RecordDocumentReleased(store))

    @Bean
    @Suppress("LongParameterList")
    fun documentHeldConsumer(
        @Qualifier("documentHeldSubscription") subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("documentHeldHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        inboxConsumer(
            subscription,
            ConsoleEvents.DOCUMENT_HELD_TYPE,
            catalogue,
            reader,
            scope,
            handler,
            rebuildRequired,
            clock,
        )

    @Bean
    @Suppress("LongParameterList")
    fun documentReleasedConsumer(
        @Qualifier("documentReleasedSubscription") subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("documentReleasedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        inboxConsumer(
            subscription,
            ConsoleEvents.DOCUMENT_RELEASED_TYPE,
            catalogue,
            reader,
            scope,
            handler,
            rebuildRequired,
            clock,
        )

    /**
     * The one replay delivery of the service. `JdbcReplayDelivery` applies every item of the service's one work queue
     * through its inbox, so the inbox is not a bean (a bean would be polled a second time), has the same consumer name
     * and schema as the polled consumers, and dispatches on the envelope type.
     */
    @Bean
    @Suppress("LongParameterList")
    fun consoleBffReplayDelivery(
        queue: WorkQueue,
        scope: TenantScope,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        @Qualifier("documentHeldHandler") held: EventHandler,
        @Qualifier("documentReleasedHandler") released: EventHandler,
        @Qualifier("masterDataResolvedHandler") masterData: EventHandler,
        @Qualifier("periodDueDateChangedHandler") dueDate: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): ReplayDelivery {
        val dispatch = replayDispatch(held, released, masterData, dueDate)
        val inbox =
            JdbcInboxConsumer(
                ConsumerSubscription(CONSUMER, ConsoleEvents.DOCUMENT_HELD_TOPIC, SCHEMA),
                catalogue,
                reader,
                scope,
                TenancyJooq.dslContext(OutboxSchema.jooqTables(SCHEMA), scope.tenants, scope.transactions),
                dispatch,
                rebuildRequired,
                clock,
            )
        return JdbcReplayDelivery(queue, scope, inbox)
    }

    @Bean
    fun rebuildRequiredSink(): RebuildRequiredSink =
        RebuildRequiredSink {
            LoggerFactory
                .getLogger(ExceptionConsumerConfiguration::class.java)
                .error(
                    "consumer {} must be rebuilt: topic {} next offset {} is at or below the watermark {}",
                    it.consumer,
                    it.topic,
                    it.nextOffset,
                    it.watermark,
                )
        }

    companion object {
        const val CONSUMER = "console-bff"
        const val SCHEMA = "console_bff"
        const val HELD_TOPIC = ConsoleEvents.DOCUMENT_HELD_TOPIC
        const val RELEASED_TOPIC = ConsoleEvents.DOCUMENT_RELEASED_TOPIC
    }
}

@Suppress("LongParameterList")
internal fun inboxConsumer(
    subscription: ConsumerSubscription,
    type: String,
    catalogue: EventCatalogue,
    reader: EventLogReader,
    scope: TenantScope,
    handler: EventHandler,
    rebuildRequired: RebuildRequiredSink,
    clock: Clock,
): JdbcInboxConsumer {
    val entry = catalogue.find(type)
    check(entry != null && entry.topic == subscription.topic) {
        "the event catalogue does not list $type on ${subscription.topic}"
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

/** The replay dispatch: each consumed type to its own handler, nothing else. */
internal fun replayDispatch(
    held: EventHandler,
    released: EventHandler,
    masterData: EventHandler,
    dueDate: EventHandler,
): EventHandler =
    TypeDispatchHandler(
        mapOf(
            ConsoleEvents.DOCUMENT_HELD_TYPE to held,
            ConsoleEvents.DOCUMENT_RELEASED_TYPE to released,
            ConsoleEvents.MASTER_DATA_RESOLVED_TYPE to masterData,
            ConsoleEvents.PERIOD_DUE_DATE_CHANGED_TYPE to dueDate,
        ),
    )

/**
 * Routes a replayed envelope to the handler of its type; a type without a handler is a `ContractViolation` (the item is
 * dead-lettered again, fail closed). A sibling consumer task adds its type to the map.
 */
internal class TypeDispatchHandler(
    private val handlers: Map<String, EventHandler>,
) : EventHandler {
    override fun handle(event: Envelope): HandleOutcome =
        (handlers[event.type] ?: throw ContractViolation("no handler for event type ${event.type}")).handle(event)
}
