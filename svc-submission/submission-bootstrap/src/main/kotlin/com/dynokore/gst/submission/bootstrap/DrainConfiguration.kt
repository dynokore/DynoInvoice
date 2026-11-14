package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqDrainBacklog
import com.dynokore.gst.submission.adapters.persistence.JooqDrainResidualSource
import com.dynokore.gst.submission.adapters.persistence.JooqReadinessCheckStore
import com.dynokore.gst.submission.adapters.persistence.JooqReadinessEntrySource
import com.dynokore.gst.submission.adapters.persistence.JooqRegisterListFactsStore
import com.dynokore.gst.submission.adapters.policy.PolicySafetyMargins
import com.dynokore.gst.submission.application.drain.DrainAuthority
import com.dynokore.gst.submission.application.drain.DrainBacklog
import com.dynokore.gst.submission.application.drain.DrainConfig
import com.dynokore.gst.submission.application.drain.DrainPeriod
import com.dynokore.gst.submission.application.drain.DrainSelection
import com.dynokore.gst.submission.application.drain.NextDespatch
import com.dynokore.gst.submission.application.drain.PreviewDrain
import com.dynokore.gst.submission.application.drain.ResidualSource
import com.dynokore.gst.submission.application.drain.SafetyMargins
import com.dynokore.gst.submission.application.pack.PackDefaultMargin
import com.dynokore.gst.submission.application.queue.AttemptOutcomeApplier
import com.dynokore.gst.submission.application.queue.DespatchCycle
import com.dynokore.gst.submission.application.queue.SentRecovery
import com.dynokore.gst.submission.application.queue.SubmissionWorker
import com.dynokore.gst.submission.application.queue.TransportClient
import com.dynokore.gst.submission.application.readiness.HoldingChecks
import com.dynokore.gst.submission.application.readiness.ReadinessEntrySource
import com.dynokore.gst.submission.application.readiness.ReadinessInputSource
import com.dynokore.gst.submission.application.readiness.ReleasedOverrideChecks
import com.dynokore.gst.submission.application.readiness.RunReadiness
import com.dynokore.gst.submission.application.register.PeriodDueDates
import com.dynokore.gst.submission.application.register.RegisterListFactsStore
import com.dynokore.platform.configresolver.PolicyReader
import com.dynokore.platform.outboxandqueue.DrainOrderAssigner
import com.dynokore.platform.outboxandqueue.PendingPartitionReader
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * `inv.drain.*` of application.yaml: no default in code. Spring Boot binds an absent Int as 0, which fails the
 * start.
 */
@ConfigurationProperties("inv.drain")
data class DrainProperties(
    val batchSize: Int,
) {
    init {
        require(batchSize > 0) { "inv.drain.batch-size must be greater than 0" }
    }
}

/**
 * TPL-18 wiring of `previewDrainPeriod` and `drainPeriod` (INV-REQ-554, INV-AC-20849). It never creates a
 * `TransportClient` or a `ReadinessInputSource`: those come from the beans of INV-T-22121 and INV-T-22209. The batch
 * size is configuration (`inv.drain.batch-size`), never code.
 */
@Suppress("TooManyFunctions")
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DrainProperties::class)
class DrainConfiguration {
    @Bean
    fun drainBacklog(
        dsl: DSLContext,
        runner: TransactionRunner,
        pending: PendingPartitionReader,
    ): DrainBacklog = JooqDrainBacklog(dsl, runner, pending)

    @Bean
    fun drainResidualSource(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): ResidualSource = JooqDrainResidualSource(dsl, runner)

    @Bean
    fun safetyMargins(
        reader: PolicyReader,
        packDefault: PackDefaultMargin,
    ): SafetyMargins = PolicySafetyMargins(reader, packDefault)

    @Bean
    fun registerListFactsStore(
        dsl: DSLContext,
        runner: TransactionRunner,
    ): RegisterListFactsStore = JooqRegisterListFactsStore(dsl, runner)

    @Bean
    fun drainSelection(
        backlog: DrainBacklog,
        dueDates: PeriodDueDates,
        margins: SafetyMargins,
    ): DrainSelection = DrainSelection(backlog, dueDates, margins, Clock.systemUTC())

    @Bean
    fun drainAuthority(replica: AuthorityReplica): DrainAuthority =
        DrainAuthority { request, deadline ->
            replica.decide(request, deadline)
        }

    @Bean
    fun drainDespatchCycle(
        worker: SubmissionWorker,
        recovery: SentRecovery,
        transport: TransportClient,
        applier: AttemptOutcomeApplier,
    ): DespatchCycle = DespatchCycle(worker, recovery, transport, applier)

    @Bean
    fun nextDespatch(cycle: DespatchCycle): NextDespatch = NextDespatch { cycle.runOnceItem() }

    @Bean
    fun readinessEntrySource(
        dsl: DSLContext,
        tenants: TenantContext,
        overrides: ReleasedOverrideChecks,
        holding: HoldingChecks,
    ): ReadinessEntrySource = JooqReadinessEntrySource(dsl, tenants, overrides, holding)

    @Bean
    fun runReadiness(
        entries: ReadinessEntrySource,
        inputs: ReadinessInputSource,
        dsl: DSLContext,
        tenants: TenantContext,
    ): RunReadiness = RunReadiness(entries, inputs, JooqReadinessCheckStore(dsl, tenants), Clock.systemUTC())

    @Bean
    fun drainConfig(properties: DrainProperties): DrainConfig = DrainConfig(properties.batchSize)

    @Bean
    @Suppress("LongParameterList")
    fun drainPeriod(
        authority: DrainAuthority,
        selection: DrainSelection,
        assigner: DrainOrderAssigner,
        despatch: NextDespatch,
        residuals: ResidualSource,
        readiness: RunReadiness,
        tenants: TenantContext,
        transactions: TransactionRunner,
        config: DrainConfig,
    ): DrainPeriod =
        DrainPeriod(
            authority,
            selection,
            assigner,
            despatch,
            residuals,
            readiness,
            tenants,
            transactions,
            config,
            Clock.systemUTC(),
        )

    @Bean
    fun previewDrain(
        authority: DrainAuthority,
        selection: DrainSelection,
        facts: RegisterListFactsStore,
    ): PreviewDrain = PreviewDrain(authority, selection, facts)
}
