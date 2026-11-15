package com.dynokore.gst.submission.bootstrap

import com.dynokore.gst.submission.adapters.persistence.JooqMeasuredTenantSource
import com.dynokore.gst.submission.adapters.persistence.JooqUnsubmittedCountsByEntity
import com.dynokore.gst.submission.application.offboarding.MeasureUnsubmittedCount
import com.dynokore.gst.submission.application.offboarding.MeasuredTenantSource
import com.dynokore.gst.submission.application.offboarding.UnsubmittedCountMeasurement
import com.dynokore.gst.submission.application.offboarding.UnsubmittedCountsByEntity
import com.dynokore.gst.submission.application.queue.TenantTransactions
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
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
import java.time.ZoneOffset
import java.util.UUID

/** `inv.unsubmitted-count.*` (12-factor III): no default in code; a cron that does not parse fails the start. */
@ConfigurationProperties("inv.unsubmitted-count")
data class UnsubmittedCountProperties(
    val cron: String,
) {
    init {
        require(CronExpression.isValidExpression(cron)) { "inv.unsubmitted-count.cron is not a valid cron" }
    }
}

/**
 * The scheduled measurement (INV-REQ-956, INV-AC-23028): for every measured tenant, on every run, it measures the
 * unsubmitted in-scope documents and writes exactly one `UnsubmittedDocumentCountMeasured` event, unchanged or not
 * (event-carried state transfer: the absence of a recent event means the feed is not current). A failure of one tenant
 * is logged (tenant id and exception class, never the message), rolls back only that tenant's event, and does not stop
 * the others; the run then fails.
 */
class UnsubmittedCountMeasurementJob(
    private val tenants: MeasuredTenantSource,
    private val measure: MeasureUnsubmittedCount,
    private val scope: TenantTransactions,
    private val outbox: OutboxWriter,
    private val clock: Clock,
) {
    /** @return the number of events written; throws after the last tenant when any tenant failed. */
    fun run(): Int {
        val failed = mutableListOf<TenantId>()
        var written = 0
        tenants.tenants().forEach { tenant ->
            try {
                publish(tenant)
                written++
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                failed += tenant
                LOG.log(
                    System.Logger.Level.ERROR,
                    "unsubmitted count measurement of tenant {0} failed: {1}",
                    tenant.value,
                    e.javaClass.name,
                )
            }
        }
        check(failed.isEmpty()) { "unsubmitted count measurement failed for ${failed.size} tenant(s)" }
        return written
    }

    private fun publish(tenant: TenantId) =
        scope.bound(tenant) {
            scope.inTransaction {
                val measurement = measure.execute(tenant)
                outbox.append(listOf(OutboundEvent(EVENT_TYPE, data(measurement))), context(tenant))
            }
        }

    private fun context(tenant: TenantId) =
        EventContext(
            tenant = tenant,
            entity = EntityRef.TENANT_WIDE,
            docUuid = null,
            transactionDate = null,
            aggregateKey = tenant.value.toString(),
            aggregateVersion = null,
            correlation = CorrelationId(UUID.randomUUID().toString()),
            causation = CausationId("unsubmitted-count-measurement:${clock.instant()}"),
        )

    companion object {
        const val NAME = "unsubmitted-count-measurement"
        const val EVENT_TYPE = "com.dynokore.gst.submission.UnsubmittedDocumentCountMeasured.v1"
        const val TOPIC = "gst.submission.unsubmitted-document-count-measured.v1"

        /** A tolerance of twice the run interval (best practice: a small multiple of the run time). */
        private const val TOLERANCE_INTERVALS = 2L

        private val LOG = System.getLogger(UnsubmittedCountMeasurementJob::class.java.name)

        /** The payload of the contract schema: `measuredAt`, `unsubmittedDocumentCount` and `entities`. */
        fun data(measurement: UnsubmittedCountMeasurement): String =
            buildString {
                append("{\"measuredAt\":\"").append(measurement.measuredAt).append('"')
                append(",\"unsubmittedDocumentCount\":").append(measurement.total)
                append(",\"entities\":[")
                measurement.entities.forEachIndexed { index, entity ->
                    if (index > 0) append(',')
                    append("{\"entityRef\":\"").append(escape(entity.entityRef)).append('"')
                    append(",\"unsubmittedDocumentCount\":").append(entity.count).append('}')
                }
                append("]}")
            }

        private fun escape(text: String): String =
            buildString {
                text.forEach { c ->
                    when {
                        c == '"' || c == '\\' -> append('\\').append(c)
                        c < ' ' -> append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
                        else -> append(c)
                    }
                }
            }

        /** The tolerance is twice the interval between two fire times of [cron]; the code holds no literal duration. */
        fun definition(
            cron: String,
            clock: Clock,
        ): JobDefinition {
            val expression = CronExpression.parse(cron)
            val first = checkNotNull(expression.next(clock.instant().atOffset(ZoneOffset.UTC)))
            val second = checkNotNull(expression.next(first))
            return JobDefinition(
                name = NAME,
                ownerService = "submission-svc",
                entityScope = JobEntityScope.PLATFORM,
                schedule = JobSchedule(cron, ZoneOffset.UTC),
                criticality = JobCriticality.STANDARD,
                tolerance = Duration.between(first, second).multipliedBy(TOLERANCE_INTERVALS),
            )
        }
    }
}

/** Wiring of the job and its ports; registers `unsubmitted-count-measurement` only when a job runtime is available. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UnsubmittedCountProperties::class)
class UnsubmittedCountMeasurementConfiguration {
    @Bean
    fun measuredTenantSource(
        dsl: DSLContext,
        platform: PlatformTenantBinding,
        principal: WorkloadPrincipal,
        transactions: TransactionRunner,
    ): MeasuredTenantSource = JooqMeasuredTenantSource(dsl, platform, principal, transactions)

    @Bean
    fun unsubmittedCountsByEntity(
        dsl: DSLContext,
        transactions: TransactionRunner,
    ): UnsubmittedCountsByEntity = JooqUnsubmittedCountsByEntity(dsl, transactions)

    @Bean
    fun unsubmittedCountMeasurementJob(
        tenants: MeasuredTenantSource,
        counts: UnsubmittedCountsByEntity,
        outbox: OutboxWriter,
        scope: TenantTransactions,
        clock: Clock,
    ) = UnsubmittedCountMeasurementJob(tenants, MeasureUnsubmittedCount(counts, clock), scope, outbox, clock)

    @Bean
    fun unsubmittedCountMeasurementJobRegistration(
        runtime: ObjectProvider<JobRuntime>,
        job: UnsubmittedCountMeasurementJob,
        properties: UnsubmittedCountProperties,
        clock: Clock,
    ) = SmartInitializingSingleton {
        runtime.ifAvailable {
            it.register(UnsubmittedCountMeasurementJob.definition(properties.cron, clock), null) { job.run() }
        }
    }
}
