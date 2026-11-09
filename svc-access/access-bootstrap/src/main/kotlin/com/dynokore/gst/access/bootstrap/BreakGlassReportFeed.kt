package com.dynokore.gst.access.bootstrap

import com.dynokore.gst.access.adapters.events.AccessEventCatalogue
import com.dynokore.gst.access.adapters.events.BreakGlassActionReportedHandler
import com.dynokore.gst.access.adapters.persistence.JooqBreakGlassReportedActions
import com.dynokore.gst.access.application.breakglass.BreakGlassEffects
import com.dynokore.gst.access.application.breakglass.BreakGlassReportObserver
import com.dynokore.gst.access.application.breakglass.BreakGlassReportedActions
import com.dynokore.gst.access.application.breakglass.BreakGlassSessions
import com.dynokore.gst.access.application.breakglass.ReportBreakGlassActionUseCase
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
import io.micrometer.core.instrument.MeterRegistry
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import tools.jackson.databind.ObjectMapper
import java.time.Clock

private val reportLog = LoggerFactory.getLogger(BreakGlassReportFeed::class.java)

/**
 * TPL-18, INV-Q-9117 A, INV-Q-9313 A: access-svc's consumer of the platform event `BreakGlassActionReported`. The
 * default consumer of service-runtime backs off when an `InboxConsumer` bean exists, so this one is primary, as the
 * consumers of masterdata-svc's `StructureFeed`; the consumer exists only when the event-log beans do.
 *
 * This is an auto-configuration ordered after `L1CompositionAutoConfiguration`: that class registers `TenantScope` and
 * `EventLogReader`, and a scanned configuration is read before every auto-configuration, so its `@ConditionalOnBean`
 * would never see them (the consumer would silently not exist). `RebuildRequiredSink` is no bean of the platform: this
 * service binds the logging default of INV-Q-10031 A and INV-Q-10834 A, and a sink of the product replaces it.
 */
@AutoConfiguration(afterName = ["com.dynokore.platform.serviceruntime.composition.L1CompositionAutoConfiguration"])
class BreakGlassReportFeed {
    @Bean
    @ConditionalOnMissingBean(RebuildRequiredSink::class)
    fun rebuildRequiredSink(): RebuildRequiredSink = LoggingRebuildRequiredSink()

    @Bean
    fun breakGlassReportedActions(
        dsl: DSLContext,
        tenants: TenantContext,
        clock: Clock,
    ): BreakGlassReportedActions = JooqBreakGlassReportedActions(dsl, tenants, clock)

    /**
     * A refused report is logged at WARN with the report id, the unlock reference and the reason, never the payload,
     * and counted. The counter name is an Inference after `gst_errors_total` (Prometheus `_total` convention), as
     * `ConsentUseCases` names its counter.
     */
    @Bean
    fun breakGlassReportObserver(registry: MeterRegistry): BreakGlassReportObserver =
        BreakGlassReportObserver { reportId, unlockRef, reason ->
            reportLog.warn(
                "break-glass report {} for unlock {} refused: {}",
                reportId,
                unlockRef,
                reason.name,
            )
            registry.counter(REJECTED_METER, "reason", reason.name).increment()
        }

    @Bean
    @Suppress("LongParameterList") // one collaborator per port of the use case, as its constructor
    fun reportBreakGlassActionUseCase(
        tx: TransactionRunner,
        sessions: BreakGlassSessions,
        reported: BreakGlassReportedActions,
        effects: BreakGlassEffects,
        observer: BreakGlassReportObserver,
        clock: Clock,
    ) = ReportBreakGlassActionUseCase(tx, sessions, reported, effects, observer, clock)

    @Bean
    fun breakGlassActionReportedHandler(
        useCase: ReportBreakGlassActionUseCase,
        mapper: ObjectMapper,
        tenants: TenantContext,
    ): EventHandler =
        BreakGlassActionReportedHandler(
            useCase,
            mapper,
            tenants,
            AccessEventCatalogue.producersOf(BreakGlassActionReportedHandler.TYPE),
        )

    @Bean
    @Primary
    @ConditionalOnBean(EventLogReader::class, TenantScope::class, RebuildRequiredSink::class)
    @Suppress("LongParameterList")
    fun breakGlassReportConsumer(
        catalogue: EventCatalogue,
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        @Qualifier("breakGlassActionReportedHandler") handler: EventHandler,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(CONSUMER, TOPIC, SCHEMA),
            catalogue,
            reader,
            scope,
            TenancyJooq.dslContext(OutboxSchema.jooqTables(SCHEMA), scope.tenants, scope.transactions),
            handler,
            rebuildRequired,
            clock,
        )

    companion object {
        const val REJECTED_METER = "break_glass_report_rejected_total"
        const val TOPIC = "gst.platform.break-glass-action-reported.v1"
        private const val CONSUMER = "access-svc"
        private const val SCHEMA = "access"
    }
}
