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

/** The run-now command (INV-REQ-1138, INV-API-3023, INV-API-3025). */
internal class JobRunNowCommand(
    private val jobs: Map<String, RegisteredJob>,
    private val core: JobCore,
    private val runs: JobRunBody,
    private val runNowExecutor: Executor,
) {
    private class RunNowPlan(
        val registered: RegisteredJob,
        val target: JobTarget,
        val now: Instant,
        val paused: PausedScope,
    )

    fun runNow(request: JobCommandRequest): JobCommandOutcome<JobRunState> =
        when (val gate = runNowGate(request)) {
            is JobGate.Closed -> gate.refusal
            is JobGate.Open -> startRun(gate.value, request)
        }

    private fun runNowGate(request: JobCommandRequest): JobGate<RunNowPlan> =
        JobGate.of(jobs[request.job], JobErrorCode.JOB_UNKNOWN).andThen { registered ->
            JobGate.of(core.pauses.authorise(registered.job, request, JobCommand.RUN_NOW), SCOPE).andThen { target ->
                val now = core.clock.instant()
                JobGate
                    .of(core.pauses.runNowOnPausedScope(registered, target, request.actor, now), SCOPE)
                    .andThen { paused -> JobGate.Open(RunNowPlan(registered, target, now, paused)) }
            }
        }

    private fun startRun(
        plan: RunNowPlan,
        request: JobCommandRequest,
    ): JobCommandOutcome<JobRunState> {
        val registered = plan.registered
        val tick = registered.ticks.tickContaining(plan.now)
        val started: Pair<JobRunState, Boolean> =
            core.scopes.inTenant(plan.target, request.actor) {
                JobJournalHooks.begin(request)
                var outcome = attemptRun(plan, tick, request)
                while (outcome == null) outcome = attemptRun(plan, tick, request)
                request.journal?.withinTransaction(JobCommandOutcome.Done(outcome.first, outcome.second))
                outcome
            }
        if (started.second) {
            val key = RunKey(registered.job, plan.target, tick, JobTrigger.MANUAL, started.first.attempt)
            val skipped = plan.paused.skipped()
            runNowExecutor.execute {
                runs.execute(registered, key, request.correlation, request.causation) { tenant, entity ->
                    (tenant to entity) in skipped
                }
            }
        }
        return JobCommandOutcome.Done(started.first, created = started.second)
    }

    /** The run state and whether this call started the run; null when a concurrent claim won and the read repeats. */
    private fun attemptRun(
        plan: RunNowPlan,
        tick: Instant,
        request: JobCommandRequest,
    ): Pair<JobRunState, Boolean>? {
        val job = plan.registered.job
        val target = plan.target
        val latest = core.state.latest(job, target, tick, JobTrigger.MANUAL)
        return if (latest != null && latest.status != JobRunStatus.FAILED) {
            core.records.runState(job, target, tick, latest, repeat = true) to false
        } else {
            val attempt = (latest?.attempt ?: 0) + 1
            if (core.state.begin(RunKey(job, target, tick, JobTrigger.MANUAL, attempt), plan.now)) {
                core.records.recordAudit(request, JobCommand.RUN_NOW, job, target, plan.paused.priorState())
                val started = RunRow(attempt, JobRunStatus.IN_PROGRESS, plan.now)
                core.records.runState(job, target, tick, started, repeat = false) to true
            } else {
                null
            }
        }
    }

    private companion object {
        val SCOPE = JobErrorCode.SCOPE_NOT_PERMITTED
    }
}
