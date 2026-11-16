package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.events.AccessPointRouteChangedHandler
import com.dynokore.gst.submission.adapters.persistence.JooqApConnectionEntities
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
 * The consumer of `AccessPointRouteChanged` that fills the connection replica (INV-REQ-2467, INV-AC-23662): the jOOQ
 * store (also the `EntityConnections` reader of the `SubmissionTypeDecided` publisher), the handler and one
 * `JdbcInboxConsumer`. The consumer refuses to be built when the catalogue does not list the event on exactly its
 * topic (fail closed).
 */
@Configuration(proxyBeanMethods = false)
class AccessPointRouteConsumerConfiguration {
    @Bean
    fun apConnectionEntities(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): JooqApConnectionEntities = JooqApConnectionEntities(dsl, runner)

    @Bean
    fun accessPointRouteChangedHandler(
        tenants: TenantContext,
        store: JooqApConnectionEntities,
    ): AccessPointRouteChangedHandler = AccessPointRouteChangedHandler(tenants, store)

    @Bean
    @Suppress("LongParameterList")
    fun accessPointRouteChangedConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        @Qualifier("accessPointRouteChangedHandler") handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer {
        val subscription = subscription()
        val entry = catalogue.find(SubmissionEvents.ACCESS_POINT_ROUTE_CHANGED_TYPE)
        check(entry != null && entry.topic == subscription.topic) {
            "the event catalogue does not list ${SubmissionEvents.ACCESS_POINT_ROUTE_CHANGED_TYPE} on " +
                SubmissionEvents.ACCESS_POINT_ROUTE_CHANGED_TOPIC
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
                "submission-svc-access-point-route",
                SubmissionEvents.ACCESS_POINT_ROUTE_CHANGED_TOPIC,
                "submission",
            )
    }
}
