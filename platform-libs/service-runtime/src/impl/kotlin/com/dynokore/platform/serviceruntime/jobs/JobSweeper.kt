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
import org.jooq.Name
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

/** The abandoned-run sweeper: records FAILED("abandoned") for runs whose instance died (INV-Q-2948). */
internal class JobSweeper(
    private val scope: TenantScope,
    private val dsl: DSLContext,
    private val inProgress: Name,
    private val jobs: Map<String, RegisteredJob>,
    private val core: JobCore,
    private val runs: JobRunBody,
) {
    private data class Candidate(
        val job: String,
        val target: JobTarget,
        val tick: Instant,
        val trigger: JobTrigger,
        val attempt: Int,
        val startedAt: Instant,
    )

    /** Returns the number swept. */
    fun sweep(): Int {
        val now = core.clock.instant()
        return abandonedCandidates().count { candidate ->
            val registered = jobs[candidate.job] ?: return@count false
            if (!candidate.startedAt.plus(registered.job.lockAtMostFor).isBefore(now)) return@count false
            val key = RunKey(registered.job, candidate.target, candidate.tick, candidate.trigger, candidate.attempt)
            runs.finish(
                registered,
                key,
                RunEnd(JobRuntime.ABANDONED, Duration.between(candidate.startedAt, now).toMillis()),
                core.records.newCorrelation(),
            )
        }
    }

    /** The IN_PROGRESS runs, oldest first, through the `<svc>_sys` view (INV-Q-4346), as the service's principal. */
    private fun abandonedCandidates(): List<Candidate> =
        scope.bindWorkload(TenantId.PLATFORM) {
            scope.transactions.inTransaction {
                val function = DSL.function(inProgress, SQLDataType.JSONB, DSL.`val`(SWEEP_BATCH))
                val json =
                    dsl
                        .select(function)
                        .fetchOne()
                        ?.value1()
                        ?.data()
                parseCandidates(json)
            }
        }

    /** The function's one jsonb array of objects (JobSchema.IN_PROGRESS_RUNS) as candidates, in its order. */
    private fun parseCandidates(json: String?): List<Candidate> =
        if (json == null) {
            emptyList()
        } else {
            JSON.readTree(json).values().map { run ->
                val tenant = TenantId(UUID.fromString(run.get("tenant_id").asString()))
                val target =
                    if (tenant.isPlatform) {
                        JobTarget.Whole
                    } else {
                        JobTarget.Entity(tenant, EntityRef(run.get("entity_ref").asString()))
                    }
                Candidate(
                    run.get("job").asString(),
                    target,
                    OffsetDateTime.parse(run.get("tick").asString()).toInstant(),
                    JobTrigger.valueOf(run.get("trigger").asString()),
                    run.get("attempt").asInt(),
                    OffsetDateTime.parse(run.get("started_at").asString()).toInstant(),
                )
            }
        }

    private companion object {
        const val SWEEP_BATCH = 500
        val JSON = ObjectMapper()
    }
}
