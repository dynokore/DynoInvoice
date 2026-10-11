package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.DeadLetterTriage
import com.dynokore.platform.outboxandqueue.EventBinding
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogLag
import com.dynokore.platform.outboxandqueue.EventLogPurge
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.InboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcDeadLetterTriage
import com.dynokore.platform.outboxandqueue.JdbcEventLogPurge
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcOutboxRelay
import com.dynokore.platform.outboxandqueue.JdbcOutboxWriter
import com.dynokore.platform.outboxandqueue.JdbcReplayDelivery
import com.dynokore.platform.outboxandqueue.JdbcRetentionPurge
import com.dynokore.platform.outboxandqueue.JdbcStalenessMonitor
import com.dynokore.platform.outboxandqueue.JdbcWorkQueue
import com.dynokore.platform.outboxandqueue.LaneLimits
import com.dynokore.platform.outboxandqueue.LaneRateLimiter
import com.dynokore.platform.outboxandqueue.OutboxRelay
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.PostgresEventBinding
import com.dynokore.platform.outboxandqueue.PostgresEventLogReader
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.ReplayDelivery
import com.dynokore.platform.outboxandqueue.Retention
import com.dynokore.platform.outboxandqueue.RetentionPurge
import com.dynokore.platform.outboxandqueue.StalenessBreachSink
import com.dynokore.platform.outboxandqueue.StalenessMonitor
import com.dynokore.platform.outboxandqueue.StalenessRule
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.serviceruntime.binding.GstProperties
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import javax.sql.DataSource

/**
 * outbox-and-queue's ports (INV-T-2875, INV-Q-3710), when the service embeds its impl half. Each implementation
 * is created only when the service declares the inputs only it has: its role DataSources ([ServiceDataSources]),
 * the topics [EventCatalogue] (from platform-contracts `event-log/topics.yaml`, TPL-18's platform-event catalogue),
 * [Retention], [LaneLimits], its [StalenessRule]s and sinks, and a consumer's [ConsumerSubscription] and
 * [EventHandler]; plus `gst.service` and `service-runtime.modules.schema`. The §12.1 [EventBinding] is the one
 * selected by `gst.bindings.event-binding` (`postgres`: [PostgresEventBinding], INV-REQ-1212).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["com.dynokore.platform.outboxandqueue.JdbcOutboxWriter"])
class OutboxAndQueueWiring {
    @Bean
    @ConditionalOnBean(TransactionRunner::class, CurrentConnection::class)
    @ConditionalOnMissingBean(TenantScope::class)
    fun tenantScope(
        tenants: TenantContext,
        transactions: TransactionRunner,
        connections: CurrentConnection,
    ): TenantScope = TenantScope(tenants, transactions, connections)

    @Bean
    @ConditionalOnBean(CurrentConnection::class, EventCatalogue::class)
    @ConditionalOnProperty(name = [Props.SERVICE, Props.SCHEMA])
    @ConditionalOnMissingBean(OutboxWriter::class)
    fun outboxWriter(
        connections: CurrentConnection,
        catalogue: EventCatalogue,
        gst: GstProperties,
        modules: ModuleProperties,
        clock: Clock,
    ): JdbcOutboxWriter = JdbcOutboxWriter(connections, modules.requireSchema(), gst.requireService(), gst.requireInstanceId(), catalogue, clock)

    @Bean
    @ConditionalOnProperty(name = [Props.EVENT_BINDING], havingValue = "postgres", matchIfMissing = true)
    @ConditionalOnBean(name = [ServiceDataSources.EVENT_LOG_RELAY])
    @ConditionalOnMissingBean(EventBinding::class)
    fun eventBinding(
        @Qualifier(ServiceDataSources.EVENT_LOG_RELAY) relay: DataSource,
    ): PostgresEventBinding = PostgresEventBinding(relay)

    @Bean
    @ConditionalOnBean(value = [TenantScope::class, EventBinding::class], name = [ServiceDataSources.WORKER])
    @ConditionalOnProperty(name = [Props.SCHEMA])
    @ConditionalOnMissingBean(OutboxRelay::class)
    fun outboxRelay(
        @Qualifier(ServiceDataSources.WORKER) worker: DataSource,
        scope: TenantScope,
        binding: EventBinding,
        modules: ModuleProperties,
        clock: Clock,
    ): JdbcOutboxRelay = JdbcOutboxRelay(worker, scope, modules.requireSchema(), binding, clock)

    @Bean
    @ConditionalOnBean(name = [ServiceDataSources.EVENT_LOG_CLAIM, ServiceDataSources.EVENT_LOG_READER])
    @ConditionalOnMissingBean(EventLogReader::class)
    fun eventLogReader(
        @Qualifier(ServiceDataSources.EVENT_LOG_CLAIM) claim: DataSource,
        @Qualifier(ServiceDataSources.EVENT_LOG_READER) reader: DataSource,
    ): PostgresEventLogReader = PostgresEventLogReader(claim, reader)

    @Bean
    @ConditionalOnBean(value = [LaneLimits::class], name = [ServiceDataSources.WORKER])
    @ConditionalOnProperty(name = [Props.SCHEMA])
    @ConditionalOnMissingBean(LaneRateLimiter::class)
    fun laneRateLimiter(
        @Qualifier(ServiceDataSources.WORKER) worker: DataSource,
        limits: LaneLimits,
        modules: ModuleProperties,
    ): LaneRateLimiter = LaneRateLimiter(worker, modules.requireSchema(), limits)

    @Bean
    @ConditionalOnBean(value = [TenantScope::class, LaneRateLimiter::class], name = [ServiceDataSources.WORKER])
    @ConditionalOnMissingBean(WorkQueue::class)
    fun workQueue(
        @Qualifier(ServiceDataSources.WORKER) worker: DataSource,
        scope: TenantScope,
        limiter: LaneRateLimiter,
        modules: ModuleProperties,
        clock: Clock,
    ): JdbcWorkQueue = JdbcWorkQueue(worker, scope, modules.requireSchema(), limiter, clock)

    @Bean
    @ConditionalOnBean(value = [TenantScope::class], name = [ServiceDataSources.WORKER])
    @ConditionalOnProperty(name = [Props.SCHEMA])
    @ConditionalOnMissingBean(DeadLetterTriage::class)
    fun deadLetterTriage(
        @Qualifier(ServiceDataSources.WORKER) worker: DataSource,
        scope: TenantScope,
        modules: ModuleProperties,
        clock: Clock,
    ): JdbcDeadLetterTriage = JdbcDeadLetterTriage(worker, scope, modules.requireSchema(), clock)

    @Bean
    @ConditionalOnBean(value = [Retention::class], name = [ServiceDataSources.WORKER])
    @ConditionalOnProperty(name = [Props.SCHEMA])
    @ConditionalOnMissingBean(RetentionPurge::class)
    fun retentionPurge(
        @Qualifier(ServiceDataSources.WORKER) worker: DataSource,
        retention: Retention,
        modules: ModuleProperties,
        clock: Clock,
    ): JdbcRetentionPurge = JdbcRetentionPurge(worker, modules.requireSchema(), retention, clock)

    @Bean
    @ConditionalOnBean(value = [Retention::class, EventCatalogue::class], name = [ServiceDataSources.EVENT_LOG_PURGE])
    @ConditionalOnMissingBean(EventLogPurge::class)
    fun eventLogPurge(
        @Qualifier(ServiceDataSources.EVENT_LOG_PURGE) purge: DataSource,
        catalogue: EventCatalogue,
        retention: Retention,
        clock: Clock,
    ): JdbcEventLogPurge = JdbcEventLogPurge(purge, catalogue, retention, clock)

    @Bean
    @ConditionalOnBean(
        value = [EventLogLag::class, EventCatalogue::class, StalenessRule::class, StalenessBreachSink::class],
        name = [ServiceDataSources.WORKER],
    )
    @ConditionalOnProperty(name = [Props.SCHEMA])
    @ConditionalOnMissingBean(StalenessMonitor::class)
    @Suppress("LongParameterList")
    fun stalenessMonitor(
        @Qualifier(ServiceDataSources.WORKER) worker: DataSource,
        lag: EventLogLag,
        catalogue: EventCatalogue,
        rules: List<StalenessRule>,
        sink: StalenessBreachSink,
        modules: ModuleProperties,
        clock: Clock,
    ): JdbcStalenessMonitor = JdbcStalenessMonitor(worker, lag, catalogue, modules.requireSchema(), rules, clock, sink)

    /** One consumer per service by default; a service with several declares its own [JdbcInboxConsumer]s. */
    @Bean
    @ConditionalOnBean(
        value = [ConsumerSubscription::class, EventHandler::class, RebuildRequiredSink::class, EventLogReader::class, TenantScope::class],
        name = [ServiceDataSources.WORKER],
    )
    @ConditionalOnMissingBean(InboxConsumer::class)
    @Suppress("LongParameterList")
    fun inboxConsumer(
        subscription: ConsumerSubscription,
        reader: EventLogReader,
        @Qualifier(ServiceDataSources.WORKER) worker: DataSource,
        scope: TenantScope,
        handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer = JdbcInboxConsumer(subscription, reader, worker, scope, handler, rebuildRequired, clock)

    @Bean
    @ConditionalOnBean(WorkQueue::class, TenantScope::class, JdbcInboxConsumer::class)
    @ConditionalOnMissingBean(ReplayDelivery::class)
    fun replayDelivery(
        queue: WorkQueue,
        scope: TenantScope,
        inbox: JdbcInboxConsumer,
    ): JdbcReplayDelivery = JdbcReplayDelivery(queue, scope, inbox)
}
