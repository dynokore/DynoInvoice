package com.dynokore.platform.serviceruntime.composition

import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.DeadLetterTriage
import com.dynokore.platform.outboxandqueue.EventBinding
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventLogLag
import com.dynokore.platform.outboxandqueue.EventLogPresence
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
import com.dynokore.platform.outboxandqueue.JdbcUnrelayedEventCheck
import com.dynokore.platform.outboxandqueue.JdbcWorkQueue
import com.dynokore.platform.outboxandqueue.LaneLimits
import com.dynokore.platform.outboxandqueue.LaneRateLimiter
import com.dynokore.platform.outboxandqueue.OutboxRelay
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.PostgresEventBinding
import com.dynokore.platform.outboxandqueue.PostgresEventLogPresence
import com.dynokore.platform.outboxandqueue.PostgresEventLogReader
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.ReplayDelivery
import com.dynokore.platform.outboxandqueue.Retention
import com.dynokore.platform.outboxandqueue.RetentionPurge
import com.dynokore.platform.outboxandqueue.StalenessBreachSink
import com.dynokore.platform.outboxandqueue.StalenessMonitor
import com.dynokore.platform.outboxandqueue.StalenessRule
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.UnrelayedEventCheck
import com.dynokore.platform.outboxandqueue.UnrelayedEventSink
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.serviceruntime.binding.GstProperties
import com.dynokore.platform.serviceruntime.staleness.LoggingStalenessBreachSink
import com.dynokore.platform.serviceruntime.staleness.OnStalenessRules
import com.dynokore.platform.serviceruntime.staleness.StalenessCheckSchedule
import com.dynokore.platform.serviceruntime.staleness.StalenessGauges
import com.dynokore.platform.serviceruntime.staleness.StalenessProperties
import com.dynokore.platform.serviceruntime.staleness.StalenessRules
import com.dynokore.platform.tenancykernel.CurrentConnection
import com.dynokore.platform.tenancykernel.TenantAwareDataSource
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import com.dynokore.platform.tenancykernel.TransactionRunnerImpl
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import java.time.Clock
import javax.sql.DataSource

/**
 * outbox-and-queue's ports (INV-T-2875, INV-Q-3710), when the service embeds its impl half. Each implementation
 * is created only when the service declares the inputs only it has: its role DataSources ([ServiceDataSources]),
 * the topics [EventCatalogue] (from platform-contracts `event-log/topics.yaml`, TPL-18's platform-event catalogue),
 * [Retention], [LaneLimits], its staleness rules ([StalenessProperties] or [StalenessRule]s), and a consumer's [ConsumerSubscription] and
 * [EventHandler]; plus `gst.service` and `service-runtime.modules.schema`. The §12.1 [EventBinding] is the one
 * selected by `gst.bindings.event-binding` (`postgres`: [PostgresEventBinding], INV-REQ-1212).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = ["com.dynokore.platform.outboxandqueue.JdbcOutboxWriter"])
@EnableConfigurationProperties(StalenessProperties::class, UnrelayedEventProperties::class)
class OutboxAndQueueWiring {
    /**
     * The service's [TenantScope] over its application transactions, with the service's workload principal for
     * background work (INV-REQ-854 b1, coordinator ruling INV-Q-4268): `svc:<gst.service>` until INV-T-3981 passes the
     * real one.
     */
    @Bean
    @ConditionalOnBean(TransactionRunner::class, CurrentConnection::class)
    @ConditionalOnProperty(name = [Props.SERVICE])
    @ConditionalOnMissingBean(TenantScope::class)
    fun tenantScope(
        tenants: TenantContext,
        transactions: TransactionRunner,
        connections: CurrentConnection,
        gst: GstProperties,
    ): TenantScope = TenantScope(tenants, transactions, connections, workloadPrincipal(gst))

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
    @ConditionalOnBean(TenantScope::class, DataSource::class)
    @ConditionalOnMissingBean(EventBinding::class)
    fun eventBinding(
        @Qualifier(ServiceDataSources.EVENT_LOG) eventLog: ObjectProvider<DataSource>,
        dataSource: DataSource,
        scope: TenantScope,
    ): PostgresEventBinding = PostgresEventBinding(eventLogScope(eventLog.getIfAvailable { dataSource }, scope))

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
    @ConditionalOnBean(TenantScope::class, DataSource::class)
    @ConditionalOnMissingBean(EventLogReader::class)
    fun eventLogReader(
        @Qualifier(ServiceDataSources.EVENT_LOG) eventLog: ObjectProvider<DataSource>,
        dataSource: DataSource,
        scope: TenantScope,
    ): PostgresEventLogReader {
        val source = eventLog.getIfAvailable { dataSource }
        return PostgresEventLogReader(source, eventLogScope(source, scope))
    }

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

    /** The event-log component's purge (INV-MOD-2748); its retention is the event log's own row (INV-Q-4262). */
    @Bean
    @ConditionalOnBean(Retention::class, EventCatalogue::class, DataSource::class)
    @ConditionalOnMissingBean(EventLogPurge::class)
    fun eventLogPurge(
        @Qualifier(ServiceDataSources.EVENT_LOG) eventLog: ObjectProvider<DataSource>,
        dataSource: DataSource,
        catalogue: EventCatalogue,
    ): JdbcEventLogPurge = JdbcEventLogPurge(eventLog.getIfAvailable { dataSource }, catalogue)

    /**
     * The service's staleness rules (INV-REQ-1165): `service-runtime.staleness.rules` and any [StalenessRule] beans,
     * defaulted and capped by the provisional thresholds (INV-Q-2991, INV-Q-2987); a longer value stops start-up.
     */
    @Bean
    @Conditional(OnStalenessRules::class)
    @ConditionalOnMissingBean(StalenessRules::class)
    fun stalenessRules(
        properties: StalenessProperties,
        declared: ObjectProvider<StalenessRule>,
    ): StalenessRules = StalenessRules.resolve(properties.rules, declared.orderedStream().toList())

    /** A service's own [StalenessBreachSink] replaces the logging default (the incident is the alert rule's, INV-Q-3030). */
    @Bean
    @ConditionalOnBean(
        value = [EventLogLag::class, EventCatalogue::class, StalenessRules::class],
        name = [ServiceDataSources.WORKER],
    )
    @ConditionalOnProperty(name = [Props.SCHEMA])
    @ConditionalOnMissingBean(StalenessMonitor::class)
    @Suppress("LongParameterList")
    fun stalenessMonitor(
        @Qualifier(ServiceDataSources.WORKER) worker: DataSource,
        lag: EventLogLag,
        catalogue: EventCatalogue,
        rules: StalenessRules,
        sink: ObjectProvider<StalenessBreachSink>,
        modules: ModuleProperties,
        clock: Clock,
    ): JdbcStalenessMonitor = JdbcStalenessMonitor(worker, lag, catalogue, modules.requireSchema(), rules.rules, clock, sink.getIfAvailable { LoggingStalenessBreachSink() })

    /** The lag and threshold gauges per (consumer, event type) (INV-T-3005, INV-Q-3030 (a)). */
    @Bean
    @ConditionalOnBean(StalenessMonitor::class, StalenessRules::class)
    @ConditionalOnMissingBean(StalenessGauges::class)
    fun stalenessGauges(
        monitor: StalenessMonitor,
        rules: StalenessRules,
        registry: MeterRegistry,
    ): StalenessGauges = StalenessGauges(monitor, rules.rules, registry)

    @Bean
    @ConditionalOnBean(StalenessGauges::class)
    @ConditionalOnMissingBean(StalenessCheckSchedule::class)
    fun stalenessCheckSchedule(
        gauges: StalenessGauges,
        properties: StalenessProperties,
    ): StalenessCheckSchedule = StalenessCheckSchedule(gauges, properties.checkInterval)

    /** `eventlog.events_missing` on the event-log DataSource (owner ruling INV-Q-4350 (A)). */
    @Bean
    @ConditionalOnBean(DataSource::class)
    @ConditionalOnMissingBean(EventLogPresence::class)
    fun eventLogPresence(
        @Qualifier(ServiceDataSources.EVENT_LOG) eventLog: ObjectProvider<DataSource>,
        dataSource: DataSource,
    ): PostgresEventLogPresence = PostgresEventLogPresence(eventLog.getIfAvailable { dataSource })

    /**
     * The check of outbox rows marked published whose event never reached the event log (owner rulings INV-Q-4334 (B),
     * INV-Q-4350 (A)), on the same background connection as the staleness monitor. On by default with the 1-hour margin
     * (coordinator ruling INV-Q-4403 (A)); an invalid margin stops start-up. A service's own [UnrelayedEventSink]
     * replaces the logging default.
     */
    @Bean
    @ConditionalOnBean(value = [TenantScope::class, EventLogPresence::class], name = [ServiceDataSources.WORKER])
    @ConditionalOnProperty(name = [Props.SCHEMA, Props.SERVICE])
    @ConditionalOnMissingBean(UnrelayedEventCheck::class)
    @Suppress("LongParameterList")
    fun unrelayedEventCheck(
        @Qualifier(ServiceDataSources.WORKER) worker: DataSource,
        scope: TenantScope,
        presence: EventLogPresence,
        properties: UnrelayedEventProperties,
        retention: ObjectProvider<Retention>,
        sink: ObjectProvider<UnrelayedEventSink>,
        modules: ModuleProperties,
        gst: GstProperties,
    ): JdbcUnrelayedEventCheck = JdbcUnrelayedEventCheck(
        worker,
        scope,
        modules.requireSchema(),
        gst.requireService(),
        presence,
        properties.settings(retention.ifAvailable),
        sink.getIfAvailable { LoggingUnrelayedEventSink() },
    )

    /** The open-findings gauge (as the staleness gauges, INV-REQ-1165). */
    @Bean
    @ConditionalOnBean(UnrelayedEventCheck::class)
    @ConditionalOnProperty(name = [Props.SERVICE])
    @ConditionalOnMissingBean(UnrelayedEventGauge::class)
    fun unrelayedEventGauge(
        check: UnrelayedEventCheck,
        gst: GstProperties,
        registry: MeterRegistry,
    ): UnrelayedEventGauge = UnrelayedEventGauge(check, gst.requireService(), registry)

    @Bean
    @ConditionalOnBean(UnrelayedEventGauge::class)
    @ConditionalOnMissingBean(UnrelayedEventCheckSchedule::class)
    fun unrelayedEventCheckSchedule(
        gauge: UnrelayedEventGauge,
        properties: UnrelayedEventProperties,
    ): UnrelayedEventCheckSchedule = UnrelayedEventCheckSchedule(gauge, properties.checkInterval)

    /** One consumer per service by default; a service with several declares its own [JdbcInboxConsumer]s. */
    @Bean
    @ConditionalOnBean(
        value = [
            ConsumerSubscription::class, EventCatalogue::class, EventHandler::class, RebuildRequiredSink::class,
            EventLogReader::class, TenantScope::class,
        ],
        name = [ServiceDataSources.WORKER],
    )
    @ConditionalOnMissingBean(InboxConsumer::class)
    @Suppress("LongParameterList")
    fun inboxConsumer(
        subscription: ConsumerSubscription,
        catalogue: EventCatalogue,
        reader: EventLogReader,
        @Qualifier(ServiceDataSources.WORKER) worker: DataSource,
        scope: TenantScope,
        handler: EventHandler,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
    ): JdbcInboxConsumer = JdbcInboxConsumer(subscription, catalogue, reader, worker, scope, handler, rebuildRequired, clock)

    @Bean
    @ConditionalOnBean(WorkQueue::class, TenantScope::class, JdbcInboxConsumer::class)
    @ConditionalOnMissingBean(ReplayDelivery::class)
    fun replayDelivery(
        queue: WorkQueue,
        scope: TenantScope,
        inbox: JdbcInboxConsumer,
    ): JdbcReplayDelivery = JdbcReplayDelivery(queue, scope, inbox)

    private companion object {
        /** The workload principal of background work until INV-T-3981 passes the real one (INV-Q-4268). */
        fun workloadPrincipal(gst: GstProperties): String = "svc:${gst.requireService()}"

        /**
         * A [TenantScope] over the event log's own transactions (its own [TransactionRunnerImpl], so an event-log
         * transaction never joins an application one), with the service's [TenantScope.workloadPrincipal].
         */
        fun eventLogScope(
            eventLog: DataSource,
            scope: TenantScope,
        ): TenantScope {
            val transactions = TransactionRunnerImpl(TenantAwareDataSource(eventLog), scope.tenants)
            return TenantScope(scope.tenants, transactions, transactions, scope.workloadPrincipal, scope.platform)
        }
    }
}
