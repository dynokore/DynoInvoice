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

/** Which pauses hold for a job target, and who may command a scope. */
internal class JobPauseRules(
    private val scope: TenantScope,
    private val scopes: JobScopes,
    private val state: JdbcJobState,
    private val authorizer: JobCommandAuthorizer,
    private val deadlines: DeadlineSource,
) {
    /**
     * The command's target, or null when the actor may not command the job at that scope (JOB-002): an entity's
     * share of an ENTITY-scoped job needs the entity grant; the whole job, and any command on a PLATFORM-scoped
     * job with or without an entity, the platform operator role (INV-Q-2985, INV-Q-3032, INV-Q-3537). The
     * platform operator giving an entity for a PLATFORM-scoped job is a contract violation (400).
     */
    fun authorise(
        job: JobDefinition,
        request: JobCommandRequest,
        command: JobCommand,
    ): JobTarget? {
        val entity = request.entity
        return if (entity == null || job.entityScope == JobEntityScope.PLATFORM) {
            platformTarget(request, command, entity)
        } else {
            val target = JobTarget.Entity(scope.tenants.current(), entity)
            target.takeIf { authorizer.mayCommandEntity(request.actor, it.tenant, it.entity, command) }
        }
    }

    private fun platformTarget(
        request: JobCommandRequest,
        command: JobCommand,
        entity: EntityRef?,
    ): JobTarget? {
        if (!authorizer.isPlatformOperator(request.actor, command)) return null
        require(entity == null) { "a PLATFORM-scoped job has no entity share" }
        return JobTarget.Whole
    }

    /**
     * The latest end of a pause of a deadline-critical job: the nearest due date minus the safety margin
     * (INV-Q-2946).
     */
    fun deadlineBound(
        job: JobDefinition,
        target: JobTarget,
    ): Instant? =
        if (job.criticality == JobCriticality.DEADLINE_CRITICAL) {
            deadlines.nearestDueDate(job, target)?.minus(deadlines.safetyMargin(job, target))
        } else {
            null
        }

    /**
     * The open pause of (job, target) that still holds at [now], in the target's transaction. A pause past its
     * end time, or lapsed at the deadline bound, is ended where it ended and does not hold (INV-Q-2946).
     */
    fun livePause(
        job: JobDefinition,
        target: JobTarget,
        now: Instant,
        bound: Instant?,
    ): PauseRow? {
        val open = state.openPause(job, target) ?: return null
        val end = if (bound != null && bound.isBefore(open.until)) bound else open.until
        return open.takeIf { now.isBefore(end) } ?: run {
            state.endPause(job, target, maxOf(end, open.pausedAt))
            null
        }
    }

    fun peekPause(
        job: JobDefinition,
        target: JobTarget,
        at: Instant,
        bound: Instant?,
    ): PauseRow? =
        state.openPause(job, target)?.takeIf {
            at.isBefore(if (bound != null && bound.isBefore(it.until)) bound else it.until)
        }

    /**
     * Whether a firing for [target] skips: its own pause, lapsing at its deadline bound, or for an entity the
     * job-wide pause, lapsing at the whole job's bound (the earliest over every entity, INV-Q-3531); both are
     * re-checked at every firing because due dates move (INV-REQ-1138 b2-b3, INV-Q-2946).
     */
    fun isPaused(
        job: JobDefinition,
        target: JobTarget,
        at: Instant,
        endLapsed: Boolean,
    ): Boolean {
        val bound = deadlineBound(job, target)
        val own =
            scopes.inRun(job, target) {
                if (endLapsed) livePause(job, target, at, bound) else peekPause(job, target, at, bound)
            }
        return own != null || (target is JobTarget.Entity && wideHolds(job, at, endLapsed))
    }

    private fun wideHolds(
        job: JobDefinition,
        at: Instant,
        endLapsed: Boolean,
    ): Boolean {
        val wideBound = deadlineBound(job, JobTarget.Whole)
        val wide =
            scopes.inRun(job, JobTarget.Whole) {
                if (endLapsed) {
                    livePause(job, JobTarget.Whole, at, wideBound)
                } else {
                    peekPause(job, JobTarget.Whole, at, wideBound)
                }
            }
        return wide != null
    }

    /**
     * The one place where a run-now meets a paused scope (owner decision INV-Q-3533, ruling INV-Q-3563). A
     * run-now processes its own scope's items once despite a pause its caller can lift (the actor may resume
     * that key, through [JobCommandAuthorizer]) and leaves the pause in force for scheduled firings. A pause the
     * caller cannot lift stays in force: a whole-job run-now skips the entities under such pauses, and the
     * audit lists them. A scope wholly covered by such pauses, taken together (the job-wide pause, the requested
     * entity's own, or per-entity pauses over every entity of a whole-job run; INV-Q-3567), returns `null`:
     * refused with JOB-002, no run recorded. Every pause in force is audited as prior state.
     */
    fun runNowOnPausedScope(
        registered: RegisteredJob,
        target: JobTarget,
        actor: String,
        now: Instant,
    ): PausedScope? {
        val job = registered.job
        val wide =
            scopes.inTenant(
                JobTarget.Whole,
                actor,
            ) { peekPause(job, JobTarget.Whole, now, deadlineBound(job, JobTarget.Whole)) }
        val wideRefused = wide != null && !authorizer.isPlatformOperator(actor, JobCommand.RESUME)
        return if (wideRefused) null else scopeOf(registered, target, actor, now, wide)
    }

    private fun scopeOf(
        registered: RegisteredJob,
        target: JobTarget,
        actor: String,
        now: Instant,
        wide: PauseRow?,
    ): PausedScope? {
        val pauses = mutableListOf<Pair<TenantId, PauseInForce>>()
        if (wide != null) pauses += TenantId.PLATFORM to PauseInForce(null, wide.until, wide.pausedAt, skipped = false)
        val shares =
            when (target) {
                is JobTarget.Entity -> listOf(target)
                JobTarget.Whole -> registered.entities?.entities(registered.job).orEmpty()
            }
        pauses +=
            shares.mapNotNull { share -> sharePause(registered.job, share, actor, now)?.let { share.tenant to it } }
        // a share the caller cannot lift refuses an entity run-now (INV-Q-3563)
        val blocked = target is JobTarget.Entity && pauses.any { it.second.skipped }
        // INV-Q-3567: the union of the unliftable pauses covering every item of the scope refuses it.
        val covered = shares.isNotEmpty() && pauses.count { it.second.skipped } == shares.size
        return if (blocked || covered) null else PausedScope(pauses)
    }

    /** The pause in force on [share], skipped when the caller cannot lift it; null when it has none. */
    private fun sharePause(
        job: JobDefinition,
        share: JobTarget.Entity,
        actor: String,
        now: Instant,
    ): PauseInForce? {
        val own = scopes.inTenant(share, actor) { peekPause(job, share, now, deadlineBound(job, share)) }
        val liftable = own != null && authorizer.mayCommandEntity(actor, share.tenant, share.entity, JobCommand.RESUME)
        return own?.let { PauseInForce(share.entity, it.until, it.pausedAt, skipped = !liftable) }
    }
}
