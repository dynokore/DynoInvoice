package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.events.JacksonEntityProfileChangedDataReader
import com.dynokore.gst.submission.adapters.persistence.JooqObligationStartDateStore
import com.dynokore.gst.submission.application.startdate.EntityProfileChangedDataReader
import com.dynokore.gst.submission.application.startdate.EntityProfileChangedHandler
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
 * The consumer of `EntityProfileChanged` (INV-REQ-335, INV-AC-22202): the Jackson reader, the jOOQ obligation start
 * date store, the handler and one `JdbcInboxConsumer` the event loop polls, the way [PeriodRecordsConsumer] declares
 * its own. The consumer refuses to be built when the catalogue does not list the event on exactly this topic (fail
 * closed).
 */
@Configuration(proxyBeanMethods = false)
class EntityProfileChangedConsumer {
    @Bean
    fun entityProfileChangedDataReader(): EntityProfileChangedDataReader = JacksonEntityProfileChangedDataReader()

    @Bean
    fun obligationStartDateStore(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): JooqObligationStartDateStore = JooqObligationStartDateStore(dsl, runner)

    @Bean
    fun entityProfileChangedHandler(
        tenants: TenantContext,
        reader: EntityProfileChangedDataReader,
        store: JooqObligationStartDateStore,
    ): EntityProfileChangedHandler = EntityProfileChangedHandler(tenants, reader, store)

    @Bean
    @Suppress("LongParameterList")
    fun entityProfileChangedInboxConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("entityProfileChangedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer {
        val subscription = subscription()
        val entry = catalogue.find(SubmissionEvents.ENTITY_PROFILE_CHANGED_TYPE)
        check(entry != null && entry.topic == SubmissionEvents.ENTITY_PROFILE_CHANGED_TOPIC) {
            "the event catalogue does not list ${SubmissionEvents.ENTITY_PROFILE_CHANGED_TYPE} on " +
                SubmissionEvents.ENTITY_PROFILE_CHANGED_TOPIC
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

        /** Not a bean (see [ExtensionConsumerConfiguration.subscription]). */
        fun subscription(): ConsumerSubscription =
            ConsumerSubscription(CONSUMER, SubmissionEvents.ENTITY_PROFILE_CHANGED_TOPIC, SCHEMA)
    }
}
