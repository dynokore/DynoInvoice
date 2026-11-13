package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqBacklogVersionSource
import com.dynokore.gst.submission.application.backlog.BacklogValuesPublication
import com.dynokore.gst.submission.application.backlog.BacklogVersionSource
import com.dynokore.gst.submission.application.compliance.ComplianceTenantSource
import com.dynokore.gst.submission.application.queue.TenantTransactions
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.serviceruntime.jobs.JobCriticality
import com.dynokore.platform.serviceruntime.jobs.JobDefinition
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.jobs.JobSchedule
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
import java.time.format.DateTimeParseException

/**
 * `inv.backlog-evaluation.*` (INV-AC-20583): no default in code; a cron that does not parse, and a tolerance that is
 * not a positive ISO-8601 duration, fail the start.
 */
@ConfigurationProperties("inv.backlog-evaluation")
class BacklogEvaluationProperties(
    val cron: String,
    tolerance: String,
) {
    /** Parsed with [Duration.parse], so only ISO-8601 is accepted (a bare number would bind as milliseconds). */
    val tolerance: Duration =
        try {
            Duration.parse(tolerance.trim())
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("inv.backlog-evaluation.tolerance is not an ISO-8601 duration", e)
        }

    init {
        require(CronExpression.isValidExpression(cron)) { "inv.backlog-evaluation.cron is not a valid cron" }
        require(!this.tolerance.isZero && !this.tolerance.isNegative) {
            "inv.backlog-evaluation.tolerance must be positive"
        }
    }
}

/** The three ports the job reads and writes through. */
data class BacklogEvaluationPorts(
    val tenants: ComplianceTenantSource,
    val source: BacklogVersionSource,
    val outbox: OutboxWriter,
)

/**
 * The scheduled backlog evaluation (INV-REQ-1370, INV-AC-20583): per tenant it reads the version rows and publishes one
 * `BacklogValuesMeasured` event per entity and period. A failure of one tenant is logged (tenant id and exception
 * class, never the message), rolls back only that tenant's events, and does not stop the others; the run then fails.
 * The job registers no meter.
 */
class BacklogEvaluationJob(
    private val ports: BacklogEvaluationPorts,
    private val scope: TenantTransactions,
    private val clock: Clock,
) {
    /** @return the number of events written; throws after the last tenant when any tenant failed. */
    fun run(): Int {
        val now = clock.instant()
        val today = now.atOffset(ZoneOffset.UTC).toLocalDate()
        val failed = mutableListOf<TenantId>()
        var written = 0
        ports.tenants.tenants().forEach { tenant ->
            try {
                written += evaluate(tenant, now, today)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                failed += tenant
                LOG.log(
                    System.Logger.Level.ERROR,
                    "backlog evaluation of tenant {0} failed: {1}",
                    tenant.value,
                    e.javaClass.name,
                )
            }
        }
        check(failed.isEmpty()) { "backlog evaluation failed for ${failed.size} tenant(s)" }
        return written
    }

    private fun evaluate(
        tenant: TenantId,
        now: java.time.Instant,
        today: LocalDate,
    ): Int =
        scope.bound(tenant) {
            scope.inTransaction {
                val read = ports.source.versions(tenant)
                if (read.skipped > 0) {
                    LOG.log(
                        System.Logger.Level.WARNING,
                        "backlog evaluation of tenant {0} skipped {1} version(s)",
                        tenant.value,
                        read.skipped,
                    )
                }
                BacklogValuesPublication.publish(read.versions, now, today, tenant, ports.outbox)
            }
        }

    companion object {
        const val NAME = "backlog-evaluation"
        private val LOG = System.getLogger(BacklogEvaluationJob::class.java.name)

        /** Both the cron and the tolerance come from configuration; the code holds no literal duration. */
        fun definition(
            cron: String,
            tolerance: Duration,
        ) = JobDefinition(
            name = NAME,
            ownerService = "submission-svc",
            entityScope = JobEntityScope.PLATFORM,
            schedule = JobSchedule(cron, ZoneOffset.UTC),
            criticality = JobCriticality.STANDARD,
            tolerance = tolerance,
        )
    }
}

/** Wiring of the job and its ports; registers `backlog-evaluation` only when a job runtime is available. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BacklogEvaluationProperties::class)
class BacklogEvaluationConfiguration {
    @Bean
    fun backlogVersionSource(
        dsl: DSLContext,
        transactions: TransactionRunner,
    ): BacklogVersionSource = JooqBacklogVersionSource(dsl, transactions)

    @Bean
    fun backlogEvaluationJob(
        tenants: ComplianceTenantSource,
        source: BacklogVersionSource,
        outbox: OutboxWriter,
        scope: TenantTransactions,
        clock: Clock,
    ) = BacklogEvaluationJob(BacklogEvaluationPorts(tenants, source, outbox), scope, clock)

    @Bean
    fun backlogEvaluationJobRegistration(
        runtime: ObjectProvider<JobRuntime>,
        job: BacklogEvaluationJob,
        properties: BacklogEvaluationProperties,
    ) = SmartInitializingSingleton {
        runtime.ifAvailable {
            it.register(BacklogEvaluationJob.definition(properties.cron, properties.tolerance), null) { job.run() }
        }
    }
}
