package com.dynokore.gst.transport.bootstrap

import com.dynokore.gst.transport.adapters.events.EntityProfileChangedHandler
import com.dynokore.gst.transport.adapters.persistence.JooqAccessPointConnectionStore
import com.dynokore.gst.transport.adapters.persistence.JooqIssuanceReplica
import com.dynokore.gst.transport.adapters.persistence.JooqRouteChangePublisher
import com.dynokore.gst.transport.adapters.persistence.JooqRouteUnitOfWork
import com.dynokore.gst.transport.application.AccessPointConnectionStore
import com.dynokore.gst.transport.application.ApplyEntityProfileChanged
import com.dynokore.gst.transport.application.ClearAcknowledgementRoute
import com.dynokore.gst.transport.application.ElectAcknowledgementRoute
import com.dynokore.gst.transport.application.EntityRemovalGuard
import com.dynokore.gst.transport.application.IssuanceReplica
import com.dynokore.gst.transport.application.RouteChangePublisher
import com.dynokore.gst.transport.application.RouteChangeRecorder
import com.dynokore.gst.transport.application.RouteUnitOfWork
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.RebuildRequiredIncident
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The acknowledgement routes of AP connections (INV-REQ-1310; INV-T-4982): the election and clearing use cases over the
 * connection store and the one publishing function ([RouteChangeRecorder]), which INV-T-4983 also calls for an entity
 * refs change; the issuance-mode replica and its `EntityProfileChanged` subscription on the inbox of schema
 * `transport`; and the Observe guard of an entity's removal.
 */
@Configuration(proxyBeanMethods = false)
class RouteConfiguration {
    @Bean
    fun accessPointConnectionStore(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): AccessPointConnectionStore = JooqAccessPointConnectionStore(dsl, runner)

    @Bean
    fun routeChangePublisher(outbox: OutboxWriter): RouteChangePublisher = JooqRouteChangePublisher(outbox)

    @Bean
    fun routeUnitOfWork(runner: TransactionRunner): RouteUnitOfWork = JooqRouteUnitOfWork(runner)

    @Bean
    fun routeChangeRecorder(
        store: AccessPointConnectionStore,
        publisher: RouteChangePublisher,
        unit: RouteUnitOfWork,
    ): RouteChangeRecorder = RouteChangeRecorder(store, publisher, unit)

    @Bean
    fun issuanceReplica(
        dsl: DSLContext,
        runner: TransactionRunner,
        tenants: TenantContext,
    ): IssuanceReplica = JooqIssuanceReplica(dsl, runner, tenants)

    @Bean
    fun electAcknowledgementRoute(recorder: RouteChangeRecorder): ElectAcknowledgementRoute =
        ElectAcknowledgementRoute(recorder)

    @Bean
    fun clearAcknowledgementRoute(
        recorder: RouteChangeRecorder,
        replica: IssuanceReplica,
        clock: Clock,
    ): ClearAcknowledgementRoute = ClearAcknowledgementRoute(recorder, replica, clock)

    /** Called by INV-T-4983 before an entity leaves a connection (422 SUB-019). */
    @Bean
    fun entityRemovalGuard(
        replica: IssuanceReplica,
        clock: Clock,
    ): EntityRemovalGuard = EntityRemovalGuard(replica, clock)

    @Bean
    fun applyEntityProfileChanged(replica: IssuanceReplica): ApplyEntityProfileChanged =
        ApplyEntityProfileChanged(replica)

    /**
     * The subscription to `EntityProfileChanged` as one inbox consumer of its own on the inbox, offset and dead-letter
     * tables of schema `transport` (V1), driven by the runtime's event loop like every [InboxConsumer] bean. It is
     * declared here, with its handler built inline and not as a bean, because the runtime holds other
     * [com.dynokore.platform.outboxandqueue.EventHandler] beans (its replica feeds), which rules out the
     * single-consumer default. The handler runs in the consumer's transaction with the event's tenant bound.
     */
    @Bean
    @Suppress("LongParameterList")
    fun entityProfileConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        apply: ApplyEntityProfileChanged,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, TransportEvents.ENTITY_PROFILE_TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            dsl,
            EntityProfileChangedHandler(apply),
            rebuildRequired,
            clock,
        )

    /** The default sink (INV-Q-11655 A): a sink that the product supplies replaces it. */
    @Bean
    @ConditionalOnMissingBean(RebuildRequiredSink::class)
    fun rebuildRequiredSink(): RebuildRequiredSink = LoggingRebuildRequiredSink()

    companion object {
        const val CONSUMER = "transport-svc"
        const val SCHEMA = "transport"
    }
}

/** The log-only [RebuildRequiredSink]: one ERROR line with the consumer, the topic and the offsets, nothing else. */
class LoggingRebuildRequiredSink : RebuildRequiredSink {
    override fun raise(incident: RebuildRequiredIncident) {
        LoggerFactory
            .getLogger(LoggingRebuildRequiredSink::class.java)
            .error(
                "rebuild required: consumer {} on topic {}: next offset {} is at or below purge watermark {}",
                incident.consumer,
                incident.topic,
                incident.nextOffset,
                incident.watermark,
            )
    }
}
