package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqAlertEmissionLedger
import com.dynokore.gst.submission.adapters.persistence.JooqComplianceTenantSource
import com.dynokore.gst.submission.application.compliance.AlertEmissionLedger
import com.dynokore.gst.submission.application.compliance.ComplianceAlertPublication
import com.dynokore.gst.submission.application.compliance.ComplianceAlertValues
import com.dynokore.gst.submission.application.compliance.ComplianceMetricsConfig
import com.dynokore.gst.submission.application.compliance.ComplianceTenantSource
import com.dynokore.gst.submission.application.compliance.ObligationRowSource
import com.dynokore.gst.submission.application.queue.TenantTransactions
import com.dynokore.gst.submission.domain.metrics.ComplianceMetrics
import com.dynokore.gst.submission.domain.metrics.Ratio
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobDefinition
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobSchedule
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.support.CronExpression
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/** `inv.compliance-evaluation.*` (INV-Q-16757 A): no default in code; a cron that does not parse fails the start. */
@ConfigurationProperties("inv.compliance-evaluation")
data class ComplianceEvaluationProperties(
    val cron: String,
) {
    init {
        require(CronExpression.isValidExpression(cron)) { "inv.compliance-evaluation.cron is not a valid cron" }
    }
}

/** The four ports the job reads and writes through. */
data class ComplianceEvaluationPorts(
    val tenants: ComplianceTenantSource,
    val rows: ObligationRowSource,
    val ledger: AlertEmissionLedger,
    val outbox: OutboxWriter,
)

/**
 * The daily compliance evaluation (INV-REQ-1069, owner answer INV-Q-12767 A): per tenant it reads all obligation rows,
 * computes the metrics for `today` of the clock, derives the alert values and publishes them through the emission
 * ledger. A failure of one tenant is logged (tenant id and exception class, never the message), rolls back only that
 * tenant's claims and events, and does not stop the others; the run then fails, and a re-run writes nothing twice. The
 * job registers no meter, so no metric carries an entity or period label.
 */
class ComplianceEvaluationJob(
    private val ports: ComplianceEvaluationPorts,
    private val scope: TenantTransactions,
    private val config: ComplianceMetricsConfig,
    private val clock: Clock,
) {
    /** @return the number of events written; throws after the last tenant when any tenant failed. */
    fun run(): Int {
        val today = clock.instant().atOffset(ZoneOffset.UTC).toLocalDate()
        val failed = mutableListOf<TenantId>()
        var written = 0
        ports.tenants.tenants().forEach { tenant ->
            try {
                written += evaluate(tenant, today)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                failed += tenant
                LOG.log(
                    System.Logger.Level.ERROR,
                    "compliance evaluation of tenant {0} failed: {1}",
                    tenant.value,
                    e.javaClass.name,
                )
            }
        }
        check(failed.isEmpty()) { "compliance evaluation failed for ${failed.size} tenant(s)" }
        return written
    }

    private fun evaluate(
        tenant: TenantId,
        today: LocalDate,
    ): Int =
        scope.bound(tenant) {
            scope.inTransaction {
                val report =
                    ComplianceMetrics.compute(
                        ports.rows.rows(tenant, null, null),
                        today,
                        config.manyVersionsFrom,
                    )
                ComplianceAlertPublication.publish(
                    ComplianceAlertValues.derive(report, today),
                    tenant,
                    ports.ledger,
                    ports.outbox,
                )
            }
        }

    companion object {
        private val LOG = System.getLogger(ComplianceEvaluationJob::class.java.name)

        /**
         * The tolerance is inferred (best practice: a job's tolerance is a small multiple of its run time); the cron is
         * the key `inv.compliance-evaluation.cron`, so a definition is built from it.
         */
        fun definition(cron: String) =
            JobDefinition(
                name = "compliance-evaluation",
                ownerService = "submission-svc",
                entityScope = JobEntityScope.PLATFORM,
                schedule = JobSchedule(cron, ZoneOffset.UTC),
                criticality = JobCriticality.STANDARD,
                tolerance = Duration.ofHours(1),
            )
    }
}

/** Wiring of the job and its ports (INV-Q-16751 A, INV-Q-16754 A); registers `compliance-evaluation`. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ComplianceEvaluationProperties::class)
class ComplianceEvaluationConfiguration {
    @Bean
    fun complianceTenantSource(
        dsl: DSLContext,
        platform: PlatformTenantBinding,
        principal: WorkloadPrincipal,
        transactions: TransactionRunner,
    ): ComplianceTenantSource = JooqComplianceTenantSource(dsl, platform, principal, transactions)

    @Bean
    fun alertEmissionLedger(dsl: DSLContext): AlertEmissionLedger = JooqAlertEmissionLedger(dsl)

    @Bean
    fun complianceMetricsConfig(properties: ComplianceMetricsProperties) =
        ComplianceMetricsConfig(
            properties.manyVersionsFrom,
            Ratio(properties.highAtOrAbove.numerator, properties.highAtOrAbove.denominator),
            properties.trendPeriods,
        )

    @Bean
    fun complianceEvaluationPorts(
        tenants: ComplianceTenantSource,
        rows: ObligationRowSource,
        ledger: AlertEmissionLedger,
        outbox: OutboxWriter,
    ) = ComplianceEvaluationPorts(tenants, rows, ledger, outbox)

    @Bean
    fun complianceEvaluationJob(
        ports: ComplianceEvaluationPorts,
        scope: TenantTransactions,
        config: ComplianceMetricsConfig,
        clock: Clock,
    ) = ComplianceEvaluationJob(ports, scope, config, clock)

    /** Registers the job `compliance-evaluation`; without a runtime nothing is registered. */
    @Bean
    fun complianceEvaluationJobRegistration(
        runtime: ObjectProvider<JobRuntime>,
        job: ComplianceEvaluationJob,
        properties: ComplianceEvaluationProperties,
    ) = SmartInitializingSingleton {
        runtime.ifAvailable { it.register(ComplianceEvaluationJob.definition(properties.cron), null) { job.run() } }
    }
}
