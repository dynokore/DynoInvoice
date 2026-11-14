package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.events.JacksonEntityNodeChangedDataReader
import com.dynokore.gst.submission.adapters.persistence.JooqEntityNodeStore
import com.dynokore.gst.submission.application.gstn.EntityGstns
import com.dynokore.gst.submission.application.gstn.EntityNodeChangedHandler
import com.dynokore.gst.submission.application.gstn.StoredEntityGstns
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
 * The two consumers that fill `submission.entity_node` from `OrganisationStructureChanged` and `EntityProfileChanged`
 * (INV-REQ-978, INV-AC-22283): the jOOQ store, the `EntityGstns` lookup over it, one handler per event (the reference
 * member differs) and one `JdbcInboxConsumer` per event, each with its own consumer name so the inbox of the start
 * date consumer on the same topic is untouched. The replay delivery is not wired (as for `PeriodRecordsChanged`).
 * Each consumer refuses to be built when the catalogue does not list its event on exactly its topic (fail closed).
 */
@Configuration(proxyBeanMethods = false)
class EntityNodeConsumers {
    @Bean
    fun entityNodeStore(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): JooqEntityNodeStore = JooqEntityNodeStore(dsl, runner)

    @Bean
    fun entityGstns(store: JooqEntityNodeStore): EntityGstns = StoredEntityGstns(store)

    @Bean
    fun organisationStructureChangedHandler(
        tenants: TenantContext,
        store: JooqEntityNodeStore,
    ): EntityNodeChangedHandler =
        EntityNodeChangedHandler(tenants, JacksonEntityNodeChangedDataReader("nodeRef"), store)

    @Bean
    fun entityNodeProfileChangedHandler(
        tenants: TenantContext,
        store: JooqEntityNodeStore,
    ): EntityNodeChangedHandler =
        EntityNodeChangedHandler(tenants, JacksonEntityNodeChangedDataReader("entityRef"), store)

    @Bean
    @Suppress("LongParameterList")
    fun organisationStructureChangedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("organisationStructureChangedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        consumer(
            catalogue,
            reader,
            scope,
            handler,
            rebuildRequired,
            clock,
            SubmissionEvents.ORGANISATION_STRUCTURE_CHANGED_TYPE,
            structureSubscription(),
        )

    @Bean
    @Suppress("LongParameterList")
    fun entityNodeProfileChangedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("entityNodeProfileChangedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer =
        consumer(
            catalogue,
            reader,
            scope,
            handler,
            rebuildRequired,
            clock,
            SubmissionEvents.ENTITY_PROFILE_CHANGED_TYPE,
            profileSubscription(),
        )

    @Suppress("LongParameterList")
    private fun consumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        type: String,
        subscription: ConsumerSubscription,
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

    companion object {
        private const val SCHEMA = "submission"

        /** Not a bean (see [ExtensionConsumerConfiguration.subscription]). */
        fun structureSubscription(): ConsumerSubscription =
            ConsumerSubscription(
                "submission-svc-entity-node-structure",
                SubmissionEvents.ORGANISATION_STRUCTURE_CHANGED_TOPIC,
                SCHEMA,
            )

        /** Not a bean; its name differs from the start date consumer of the same topic. */
        fun profileSubscription(): ConsumerSubscription =
            ConsumerSubscription(
                "submission-svc-entity-node-profile",
                SubmissionEvents.ENTITY_PROFILE_CHANGED_TOPIC,
                SCHEMA,
            )
    }
}
