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

/** Runs a job's body and records the run: a scheduled firing, and the run of a command. */
internal class JobRunBody(
    private val core: JobCore,
    private val election: ShedLockLeaderElection,
) {
    /**
     * One firing of [registered] for each target: a paused target is skipped, then the instance that takes the lock
     * claims the tick's `job_run` row and runs the body.
     */
    fun fire(
        registered: RegisteredJob,
        at: Instant,
    ): Map<JobTarget, FireResult> {
        val tick = registered.ticks.tickContaining(at)
        val targets = registered.entities?.entities(registered.job) ?: listOf(JobTarget.Whole)
        return targets.associateWith { target ->
            if (core.pauses.isPaused(registered.job, target, at, endLapsed = true)) {
                FireResult.SKIPPED_PAUSED
            } else {
                election.runIfLeader(registered.job, target) {
                    val key = RunKey(registered.job, target, tick, JobTrigger.SCHEDULED, 1)
                    val claimed =
                        core.scopes.inRun(
                            registered.job,
                            target,
                        ) { core.state.begin(key, core.clock.instant()) }
                    if (claimed) {
                        execute(registered, key, core.records.newCorrelation(), null)
                        FireResult.RAN
                    } else {
                        FireResult.SKIPPED_TICK_TAKEN
                    }
                } ?: FireResult.SKIPPED_NOT_LEADER
            }
        }
    }

    /**
     * Runs the body and records the run. A scheduled firing skips paused entities' items; a run-now passes
     * [skips], the entities under pauses its caller cannot lift (INV-Q-3563).
     */
    fun execute(
        registered: RegisteredJob,
        key: RunKey,
        correlation: CorrelationId,
        causation: CausationId?,
        skips: ((TenantId, EntityRef) -> Boolean)? = null,
    ) {
        val started = core.clock.instant()
        val context =
            JobRunContext(registered.job, key.target, key.trigger, key.tick, key.attempt) { tenant, entity ->
                if (skips != null) {
                    skips(tenant, entity)
                } else {
                    val target = if (tenant.isPlatform) JobTarget.Whole else JobTarget.Entity(tenant, entity)
                    core.pauses.isPaused(registered.job, target, core.clock.instant(), endLapsed = false)
                }
            }
        val failure =
            try {
                registered.body.run(context)
                null
            } catch (expected: Exception) {
                expected.javaClass.name // CS-34: the class, never the message
            }
        val millis = Duration.between(started, core.clock.instant()).toMillis().coerceAtLeast(0)
        finish(registered, key, RunEnd(failure, millis), correlation, causation)
    }

    /** Completes the run and writes its `JobRunRecorded` in one transaction; false when it was already completed. */
    fun finish(
        registered: RegisteredJob,
        key: RunKey,
        end: RunEnd,
        correlation: CorrelationId,
        causation: CausationId? = null,
    ): Boolean =
        core.scopes.inRun(registered.job, key.target) {
            val now = core.clock.instant()
            val finished = core.state.finish(key, end.failure, now, end.durationMillis)
            if (finished) {
                val nextRunAt = registered.ticks.next(now)
                val payload =
                    JobPayloads.jobRunRecorded(
                        registered.job,
                        key.target,
                        end.failure,
                        end.durationMillis,
                        key.tick,
                        nextRunAt,
                    )
                core.records.appendRunRecorded(registered, key, payload, correlation, causation)
            }
            finished
        }
}
