package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqDueTenantSource
import com.dynokore.gst.submission.adapters.persistence.JooqWaitingTenantSource
import com.dynokore.gst.submission.adapters.queue.ConfiguredRetryBoundary
import com.dynokore.gst.submission.adapters.queue.RandomJitter
import com.dynokore.gst.submission.application.queue.AttemptOutcomeApplier
import com.dynokore.gst.submission.application.queue.DueRetryRunner
import com.dynokore.gst.submission.application.queue.DueTenantSource
import com.dynokore.gst.submission.application.queue.JitterSource
import com.dynokore.gst.submission.application.queue.RetryBoundarySource
import com.dynokore.gst.submission.application.queue.RetryDueScan
import com.dynokore.gst.submission.application.queue.InFlightStore
import com.dynokore.gst.submission.application.queue.SentRecovery
import com.dynokore.gst.submission.application.queue.SentTransaction
import com.dynokore.gst.submission.application.queue.SubmissionQueue
import com.dynokore.gst.submission.application.queue.SubmissionStore
import com.dynokore.gst.submission.application.queue.TenantTransactions
import com.dynokore.gst.submission.application.queue.WaitingSweeper
import com.dynokore.gst.submission.application.queue.WaitingTenantSource
import com.dynokore.platform.outboxandqueue.WorkQueue
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** `inv.submission.*` of application.yaml: no default in code. */
@ConfigurationProperties("inv.submission")
data class SubmissionProperties(
    val retryBoundaryMinutes: Int,
) {
    init {
        // Spring Boot binds an absent Int as 0, so a missing key, a 0 and a negative value all fail the start.
        require(retryBoundaryMinutes > 0) { "inv.submission.retry-boundary-minutes must be greater than 0" }
    }
}

/**
 * TPL-18 wiring of the attempt outcome, the retry due-scan and the restart recovery (INV-T-7277). The
 * `TransportClient` adapter, the thread that calls `DespatchCycle` and `DueRetryRunner` are later slices, so those two
 * are not beans yet.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SubmissionProperties::class)
class OutcomeConfiguration(
    private val principal: WorkloadPrincipal,
) {
    @Bean
    fun tenantTransactions(
        tenants: TenantContext,
        transactions: TransactionRunner,
    ): TenantTransactions = TenantTransactions(tenants, transactions, principal)

    @Bean
    fun retryBoundarySource(properties: SubmissionProperties): RetryBoundarySource =
        ConfiguredRetryBoundary(properties.retryBoundaryMinutes)

    @Bean
    fun jitterSource(): JitterSource = RandomJitter()

    @Bean
    fun dueTenantSource(
        dsl: DSLContext,
        platform: PlatformTenantBinding,
        transactions: TransactionRunner,
    ): DueTenantSource = JooqDueTenantSource(dsl, platform, principal, transactions)

    @Bean
    fun retryDueScan(
        scope: TenantTransactions,
        store: SubmissionStore,
        queue: WorkQueue,
    ): RetryDueScan = RetryDueScan(scope, store, queue)

    @Bean
    fun dueRetryRunner(
        source: DueTenantSource,
        scope: TenantTransactions,
        scan: RetryDueScan,
    ): DueRetryRunner = DueRetryRunner(source, scope, scan)

    @Bean
    @Suppress("LongParameterList")
    fun attemptOutcomeApplier(
        boundaries: RetryBoundarySource,
        jitter: JitterSource,
        scope: TenantTransactions,
        store: SubmissionStore,
        queue: WorkQueue,
        submissions: SubmissionQueue,
    ): AttemptOutcomeApplier =
        AttemptOutcomeApplier(Clock.systemUTC(), boundaries, jitter, scope, store, queue, submissions)

    @Bean
    fun sentRecovery(
        scope: TenantTransactions,
        store: SubmissionStore,
        queue: WorkQueue,
        sent: SentTransaction,
    ): SentRecovery = SentRecovery(Clock.systemUTC(), scope, store, queue, sent)

    @Bean
    fun waitingTenantSource(
        dsl: DSLContext,
        platform: PlatformTenantBinding,
        transactions: TransactionRunner,
    ): WaitingTenantSource = JooqWaitingTenantSource(dsl, platform, principal, transactions)

    @Bean
    fun waitingSweeper(
        source: WaitingTenantSource,
        scope: TenantTransactions,
        versions: InFlightStore,
        queue: SubmissionQueue,
    ): WaitingSweeper = WaitingSweeper(source, scope, versions, queue)
}
