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

/** The pause and resume commands (INV-REQ-1138, INV-API-3023, INV-API-3025). */
internal class JobPauseCommands(
    private val jobs: Map<String, RegisteredJob>,
    private val core: JobCore,
) {
    private class PausePlan(
        val job: JobDefinition,
        val target: JobTarget,
        val now: Instant,
        val bound: Instant?,
    )

    fun pause(
        request: JobCommandRequest,
        until: Instant,
    ): JobCommandOutcome<JobPauseState> =
        when (val gate = pauseGate(request, until)) {
            is JobGate.Closed -> gate.refusal
            is JobGate.Open -> applyPause(gate.value, request, until)
        }

    private fun pauseGate(
        request: JobCommandRequest,
        until: Instant,
    ): JobGate<PausePlan> =
        JobGate.of(jobs[request.job], JobErrorCode.JOB_UNKNOWN).andThen { registered ->
            val job = registered.job
            JobGate.of(core.pauses.authorise(job, request, JobCommand.PAUSE), SCOPE).andThen { target ->
                val now = core.clock.instant()
                if (!until.isAfter(now)) {
                    JobGate.Closed(JobCommandOutcome.Refused(JobErrorCode.PAUSE_END_NOT_FUTURE))
                } else {
                    val bound = core.pauses.deadlineBound(job, target)
                    if (bound != null && until.isAfter(bound)) {
                        JobGate.Closed(JobCommandOutcome.Refused(JobErrorCode.PAUSE_PAST_DEADLINE))
                    } else {
                        JobGate.Open(PausePlan(job, target, now, bound))
                    }
                }
            }
        }

    private fun applyPause(
        plan: PausePlan,
        request: JobCommandRequest,
        until: Instant,
    ): JobCommandOutcome<JobPauseState> =
        core.scopes.inTenant(plan.target, request.actor) {
            JobJournalHooks.begin(request)
            var outcome = pauseStep(plan, request, until)
            while (outcome == null) outcome = pauseStep(plan, request, until)
            JobJournalHooks.journal(request, outcome)
        }

    /** The outcome of one read of the pause; null when a concurrent pause opened first and the read repeats. */
    private fun pauseStep(
        plan: PausePlan,
        request: JobCommandRequest,
        until: Instant,
    ): JobCommandOutcome<JobPauseState>? {
        val job = plan.job
        val target = plan.target
        val now = plan.now
        val open = core.pauses.livePause(job, target, now, plan.bound)
        return when {
            open == null && core.state.openNewPause(job, target, now, until) -> {
                core.records.recordAudit(request, JobCommand.PAUSE, job, target, """{"state":"ACTIVE"}""")
                JobCommandOutcome.Done(JobPauseState(job.name, target, true, until, now), created = true)
            }

            open == null -> {
                null
            }

            open.until == until -> {
                JobCommandOutcome.Done(JobPauseState(job.name, target, true, until, open.pausedAt), created = false)
            }

            else -> {
                core.state.replaceUntil(job, target, until)
                core.records.recordAudit(request, JobCommand.PAUSE, job, target, core.records.pausedJson(open))
                val replaced = JobPauseState(job.name, target, true, until, open.pausedAt, open.until)
                JobCommandOutcome.Done(replaced, created = true)
            }
        }
    }

    fun resume(request: JobCommandRequest): JobCommandOutcome<JobPauseState> =
        when (val gate = resumeGate(request)) {
            is JobGate.Closed -> gate.refusal
            is JobGate.Open -> applyResume(gate.value, request)
        }

    private fun resumeGate(request: JobCommandRequest): JobGate<PausePlan> =
        JobGate.of(jobs[request.job], JobErrorCode.JOB_UNKNOWN).andThen { registered ->
            val job = registered.job
            JobGate.of(core.pauses.authorise(job, request, JobCommand.RESUME), SCOPE).andThen { target ->
                val now = core.clock.instant()
                JobGate.Open(PausePlan(job, target, now, core.pauses.deadlineBound(job, target)))
            }
        }

    private fun applyResume(
        plan: PausePlan,
        request: JobCommandRequest,
    ): JobCommandOutcome<JobPauseState> {
        val job = plan.job
        val target = plan.target
        return core.scopes.inTenant(target, request.actor) {
            JobJournalHooks.begin(request)
            val open = core.pauses.livePause(job, target, plan.now, plan.bound)
            val outcome =
                if (open == null) {
                    JobCommandOutcome.Done(JobPauseState(job.name, target, false), created = false)
                } else {
                    core.state.endPause(job, target, plan.now)
                    core.records.recordAudit(request, JobCommand.RESUME, job, target, core.records.pausedJson(open))
                    JobCommandOutcome.Done(JobPauseState(job.name, target, false), created = true)
                }
            JobJournalHooks.journal(request, outcome)
        }
    }

    private companion object {
        val SCOPE = JobErrorCode.SCOPE_NOT_PERMITTED
    }
}
