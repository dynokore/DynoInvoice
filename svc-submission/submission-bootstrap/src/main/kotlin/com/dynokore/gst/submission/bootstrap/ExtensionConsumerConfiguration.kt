package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.events.JacksonPeriodExtendedDataReader
import com.dynokore.gst.submission.adapters.persistence.JooqDueDateExtensionStore
import com.dynokore.gst.submission.adapters.persistence.JooqRegisterDueDateReader
import com.dynokore.gst.submission.application.extension.DueDateExtensions
import com.dynokore.gst.submission.application.extension.PeriodExtendedDataReader
import com.dynokore.gst.submission.application.extension.PeriodExtendedHandler
import com.dynokore.gst.submission.application.register.RegisterDueDateReader
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
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The consumer of `PeriodExtended` (INV-REQ-81 behaviour 2, INV-AC-20597): the subscription, the handler over the
 * production register reader and extension store, and one `JdbcInboxConsumer` the event loop polls, the way
 * [BreakGlassFeed] declares its own. The consumer refuses to be built when the catalogue does not list the event on
 * exactly this topic (fail closed).
 */
@Configuration(proxyBeanMethods = false)
class ExtensionConsumerConfiguration {
    @Bean
    fun periodExtendedDataReader(): PeriodExtendedDataReader = JacksonPeriodExtendedDataReader()

    @Bean
    fun registerDueDateReader(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): RegisterDueDateReader = JooqRegisterDueDateReader(dsl, runner)

    @Bean
    fun dueDateExtensions(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): DueDateExtensions = JooqDueDateExtensionStore(dsl, runner)

    @Bean
    fun periodExtendedHandler(
        tenants: TenantContext,
        reader: PeriodExtendedDataReader,
        register: RegisterDueDateReader,
        store: DueDateExtensions,
    ): PeriodExtendedHandler = PeriodExtendedHandler(tenants, reader, register, store)

    @Bean
    @Suppress("LongParameterList")
    fun periodExtendedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("periodExtendedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer {
        val subscription = subscription()
        val entry = catalogue.find(SubmissionEvents.PERIOD_EXTENDED_TYPE)
        check(
            entry != null && entry.topic == SubmissionEvents.PERIOD_EXTENDED_TOPIC && subscription.topic == entry.topic,
        ) {
            "the event catalogue does not list ${SubmissionEvents.PERIOD_EXTENDED_TYPE} on " +
                SubmissionEvents.PERIOD_EXTENDED_TOPIC
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
        private const val CONSUMER = "submission-svc"
        private const val SCHEMA = "submission"

        /**
         * Not a bean: [ConsumerConfiguration.payloadReadyConsumer] takes the one `ConsumerSubscription` bean by type,
         * so a second bean would make it ambiguous. [BreakGlassFeed] builds its subscription inline for the same reason.
         */
        fun subscription(): ConsumerSubscription =
            ConsumerSubscription(CONSUMER, SubmissionEvents.PERIOD_EXTENDED_TOPIC, SCHEMA)
    }
}
