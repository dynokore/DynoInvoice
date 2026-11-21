package com.dynokore.gst.rules.bootstrap

import com.dynokore.gst.rules.adapters.events.EvaluatingSupersedingPackPort
import com.dynokore.gst.rules.adapters.events.QueueingReprocessingPort
import com.dynokore.gst.rules.adapters.events.ReprocessingEvents
import com.dynokore.gst.rules.adapters.events.ReprocessingPageRecordedHandler
import com.dynokore.gst.rules.adapters.events.ReprocessingWorker
import com.dynokore.gst.rules.adapters.events.RulesEventCatalogue
import com.dynokore.gst.rules.adapters.persistence.JooqPackCorrectionStore
import com.dynokore.gst.rules.adapters.persistence.JooqReprocessingStore
import com.dynokore.gst.rules.application.PackCorrectionPermissionPort
import com.dynokore.gst.rules.application.PackEvaluationPort
import com.dynokore.gst.rules.application.PackEventEmitter
import com.dynokore.gst.rules.application.ReprocessPopulation
import com.dynokore.gst.rules.application.RollbackPack
import com.dynokore.gst.rules.application.SupersedingPackPort
import com.dynokore.platform.outboxandqueue.ConsumerSubscription
import com.dynokore.platform.outboxandqueue.EventCatalogue
import com.dynokore.platform.outboxandqueue.EventLogReader
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
import com.dynokore.platform.outboxandqueue.JdbcWorkQueue
import com.dynokore.platform.outboxandqueue.Lane
import com.dynokore.platform.outboxandqueue.LaneLimits
import com.dynokore.platform.outboxandqueue.LaneRateLimiter
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.RateLimit
import com.dynokore.platform.outboxandqueue.RebuildRequiredSink
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration

/**
 * The wiring of pack correction and reprocessing (INV-T-4978; INV-REQ-962, 1002, 1003, 1004): the registry side of the
 * correction, the run rows and the resubmission list, the two events, the work queue with the one limit of lane
 * REPROCESSING, and the worker loop. The only place these use cases are constructed (TPL-18).
 */
@Configuration(proxyBeanMethods = false)
class ReprocessingConfiguration {
    /**
     * The limit of lane REPROCESSING and no other lane: `rules.reprocessing.rate-limit.permits` and
     * `rules.reprocessing.rate-limit.window` (ISO-8601), with no default in code. A missing, zero or negative value
     * stops the application context from starting.
     */
    @Bean
    fun laneLimits(environment: Environment): LaneLimits = laneLimitsOf(environment)

    /**
     * The queue of lane REPROCESSING, held in its own type: a bean of type [WorkQueue] would be picked up by the
     * platform's replay delivery, which would then claim reprocessing items as replay items.
     */
    @Bean
    fun reprocessingWorkQueue(
        scope: TenantScope,
        dsl: DSLContext,
        limits: LaneLimits,
        clock: Clock,
    ): ReprocessingWorkQueue =
        ReprocessingWorkQueue(
            JdbcWorkQueue(
                scope,
                dsl,
                SCHEMA,
                LaneRateLimiter(scope, dsl, SCHEMA, limits),
                clock,
                QueueingReprocessingPort.CONSUMER,
            ),
        )

    @Bean
    fun jooqReprocessingStore(
        dsl: DSLContext,
        tenants: TenantContext,
    ): JooqReprocessingStore = JooqReprocessingStore(dsl, tenants)

    @Bean
    fun jooqPackCorrectionStore(
        dsl: DSLContext,
        platform: PlatformTenantBinding,
        transactions: TransactionRunner,
        events: PackEventEmitter,
    ): JooqPackCorrectionStore = JooqPackCorrectionStore(dsl, platform, transactions, events)

    /** Publishes `PackReprocessingRequested` per entity in the command transaction of `reprocessPopulation`. */
    @Bean
    fun reprocessingEvents(
        writer: OutboxWriter,
        runs: JooqReprocessingStore,
        correction: JooqPackCorrectionStore,
        tenants: TenantContext,
    ): ReprocessingEvents = ReprocessingEvents(writer, runs, correction, tenants)

    @Bean
    fun supersedingPackPort(
        correction: JooqPackCorrectionStore,
        evaluation: PackEvaluationPort,
        tenants: TenantContext,
    ): SupersedingPackPort = EvaluatingSupersedingPackPort(correction, evaluation, tenants)

    @Bean
    fun rollbackPack(
        permission: PackCorrectionPermissionPort,
        correction: JooqPackCorrectionStore,
    ): RollbackPack = RollbackPack(permission, correction)

    @Bean
    @Suppress("LongParameterList") // the composition root names the ports of the use case (TPL-18)
    fun reprocessPopulation(
        permission: PackCorrectionPermissionPort,
        runs: JooqReprocessingStore,
        requests: ReprocessingEvents,
        queue: ReprocessingWorkQueue,
        packs: SupersedingPackPort,
    ): ReprocessPopulation =
        ReprocessPopulation(permission, runs, requests, QueueingReprocessingPort(queue.queue), runs, packs)

    /**
     * rules-svc is the subscriber of `gst.platform.pack-reprocessing-population-recorded.v1` (platform-contracts
     * event-log/topics.yaml): the handler admits each page in the consumer's transaction and puts its items on the
     * REPROCESSING lane, with its own catalogue like the dry-run sample consumer.
     */
    @Bean
    @Suppress("LongParameterList") // the consumer's collaborators are all injected platform beans
    fun reprocessingPageConsumer(
        reader: EventLogReader,
        scope: TenantScope,
        rebuildRequired: RebuildRequiredSink,
        clock: Clock,
        dsl: DSLContext,
        runs: JooqReprocessingStore,
        useCase: ReprocessPopulation,
        evaluation: PackEvaluationPort,
    ): JdbcInboxConsumer =
        JdbcInboxConsumer(
            ConsumerSubscription(RulesEventCatalogue.SERVICE, ReprocessingEvents.RECORDED_TOPIC, SCHEMA),
            EventCatalogue(listOf(ReprocessingEvents.recordedEntry())),
            reader,
            scope,
            dsl,
            ReprocessingPageRecordedHandler(runs, useCase, evaluation),
            rebuildRequired,
            clock,
        )

    @Bean
    fun reprocessingWorker(
        queue: ReprocessingWorkQueue,
        scope: TenantScope,
        useCase: ReprocessPopulation,
    ): ReprocessingWorker = ReprocessingWorker(queue.queue, scope, useCase)

    /**
     * The one worker loop: a short tick that drains what the lane limit lets through. `rules.reprocessing.worker=false`
     * switches the tick off (the tests drive [ReprocessingWorker.drain] themselves); it is on when the property is
     * absent.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = ["rules.reprocessing.worker"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun reprocessingWorkerScheduling(worker: ReprocessingWorker): ReprocessingWorkerScheduling =
        ReprocessingWorkerScheduling(worker)

    companion object {
        const val PERMITS = "rules.reprocessing.rate-limit.permits"
        const val WINDOW = "rules.reprocessing.rate-limit.window"
        private const val SCHEMA = "rules"

        /** The [LaneLimits] of the environment: lane REPROCESSING only; a bad or absent value is refused. */
        fun laneLimitsOf(environment: Environment): LaneLimits {
            val permits =
                environment.getRequiredProperty(PERMITS, Int::class.java).also {
                    check(it >= 1) { "$PERMITS must be at least 1" }
                }
            val window =
                try {
                    Duration.parse(environment.getRequiredProperty(WINDOW))
                } catch (e: DateTimeException) {
                    throw IllegalStateException("$WINDOW is not an ISO-8601 duration", e)
                }
            check(!window.isZero && !window.isNegative) { "$WINDOW must be positive" }
            return LaneLimits(mapOf(Lane.REPROCESSING to RateLimit(permits, window)))
        }
    }
}

/** The tick of the worker loop; a failure is logged and never escapes (the next tick claims again). */
class ReprocessingWorkerScheduling(
    private val worker: ReprocessingWorker,
) {
    @Scheduled(fixedDelay = TICK_MILLIS, initialDelay = TICK_MILLIS)
    fun tick() {
        runCatching { worker.drain() }.onFailure {
            log.warn("reprocessing worker tick failed: {}", it.javaClass.simpleName)
        }
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
        val log: Logger = LoggerFactory.getLogger(ReprocessingWorkerScheduling::class.java)
    }
}

/** The work queue of lane REPROCESSING; not a [WorkQueue] bean, so the platform's replay delivery skips it. */
class ReprocessingWorkQueue(
    val queue: WorkQueue,
)
