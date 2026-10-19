package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.serviceruntime.pool.PoolLane
import com.dynokore.platform.serviceruntime.pool.PoolLanes
import com.dynokore.platform.tenancykernel.SqlIdentifier
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.DatePart
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** The outbox events and audit entries of job work, and the keys they carry. */
internal class JobRecords(
    private val service: String,
    private val scope: TenantScope,
    private val outbox: OutboxWriter,
    private val audit: AuditWriter,
) {
    /** `JobRegistered` once per registered job, as the service's workload principal (INV-REQ-854 b1, INV-Q-4268). */
    fun announceRegistered(registered: Collection<RegisteredJob>) {
        val correlation = CorrelationId("job-registration:${UUID.randomUUID()}")
        // start-up is background work: the service's workload principal (INV-REQ-854 b1, INV-Q-4268)
        scope.bindWorkload(TenantId.PLATFORM) {
            scope.transactions.inTransaction {
                outbox.append(
                    registered.sortedBy { it.job.name }.map {
                        OutboundEvent(JobPayloads.JOB_REGISTERED, JobPayloads.jobRegistered(it.job))
                    },
                    context(JobTarget.Whole, "$service/jobs", correlation, CausationId(correlation.value)),
                )
            }
        }
    }

    /** `JobRunRecorded` for a completed run, inside the caller's transaction. */
    fun appendRunRecorded(
        registered: RegisteredJob,
        key: RunKey,
        payload: String,
        correlation: CorrelationId,
        causation: CausationId?,
    ) {
        outbox.append(
            listOf(OutboundEvent(JobPayloads.JOB_RUN_RECORDED, payload)),
            context(
                key.target,
                aggregateKey(registered.job),
                correlation,
                causation ?: CausationId(correlation.value),
            ),
        )
    }

    fun recordAudit(
        request: JobCommandRequest,
        command: JobCommand,
        job: JobDefinition,
        target: JobTarget,
        priorState: String?,
    ) {
        audit.record(
            auditEntry(request, command, job, target, priorState),
            context(target, aggregateKey(job), request.correlation, request.causation),
        )
    }

    private fun auditEntry(
        request: JobCommandRequest,
        command: JobCommand,
        job: JobDefinition,
        target: JobTarget,
        priorState: String?,
    ) = AuditEntry(
        actor = request.actor,
        action = command.operationId,
        scope =
            when (target) {
                JobTarget.Whole -> aggregateKey(job)
                is JobTarget.Entity -> "${aggregateKey(job)}/${target.entity}"
            },
        reason = "Idempotency-Key ${request.idempotencyKey}" + request.reason?.let { ": $it" }.orEmpty(),
        priorStateJson = priorState,
    )

    fun pausedJson(open: PauseRow) = """{"state":"PAUSED","until":"${open.until}","pausedAt":"${open.pausedAt}"}"""

    fun runState(
        job: JobDefinition,
        target: JobTarget,
        tick: Instant,
        row: RunRow,
        repeat: Boolean,
    ) = JobRunState(job.name, target, JobTrigger.MANUAL, tick, row.attempt, row.status, row.startedAt, repeat)

    fun aggregateKey(job: JobDefinition) = "$service/${job.name}"

    fun context(
        target: JobTarget,
        aggregateKey: String,
        correlation: CorrelationId,
        causation: CausationId,
    ) = EventContext(
        tenant = target.tenant,
        entity = target.entity,
        docUuid = null,
        transactionDate = null,
        aggregateKey = aggregateKey,
        aggregateVersion = null,
        correlation = correlation,
        causation = causation,
    )

    fun newCorrelation() = CorrelationId("job-run:${UUID.randomUUID()}")
}
