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
import com.dynokore.platform.tenancykernel.TenantId
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import javax.sql.DataSource

/**
 * The job contract of a service (INV-REQ-1136, INV-REQ-1138, INV-REQ-2405): registration with
 * `JobRegistered`, scheduled firings under ShedLock leader election with one `job_run` row and one
 * `JobRunRecorded` per run, run now / pause / resume, the abandoned-run sweeper and the retention purge.
 *
 * The service keeps its own scheduler (INV-REQ-1136 b2): its `@Scheduled` method calls [fire] at each
 * firing on every instance; the lock and the `job_run` key make one run per tick. Wiring:
 * - [scope]: the service's tenant binding, transactions and current connection (app role);
 * - [worker]: the service's narrow worker role (ShedLock, sweeper, purge; ARC 4.3 step 1a);
 * - [outbox] and [audit]: the service's outbox writer and audit writer (INV-REQ-1226, INV-REQ-2588).
 */
class JobRuntime(
    private val service: String,
    schema: String,
    private val scope: TenantScope,
    private val outbox: OutboxWriter,
    private val audit: AuditWriter,
    private val worker: DataSource,
    private val authorizer: JobCommandAuthorizer,
    private val deadlines: DeadlineSource,
    private val clock: Clock,
    private val runNowExecutor: Executor,
    /** job_run and ended job_pause rows are purged after this window (T30 slot answer retention: default 30 days). */
    val retention: Duration = DEFAULT_RETENTION,
) : JobCommands {
    private val schemaName = JobSchema.identifier(schema)
    private val state = JdbcJobState(scope.connections, schemaName, service)
    private val election = ShedLockLeaderElection.postgres(worker, schemaName, clock)
    private val jobs = ConcurrentHashMap<String, Registered>()

    private class Registered(
        val job: JobDefinition,
        val body: JobBody,
        val entities: JobEntitySource?,
        val ticks: CronTicks,
    )

    /**
     * Registers [job] with its [body] (INV-REQ-1136 b1); an ENTITY-scoped job also names the [entities] it
     * serves. Refused when the schedule does not parse, the name is taken, the job belongs to another
     * service, or [retention] is shorter than the job's schedule interval plus its tolerance.
     */
    fun register(
        job: JobDefinition,
        entities: JobEntitySource? = null,
        body: JobBody,
    ) {
        require(job.ownerService == service) { "a job registers in its owner service" }
        require((job.entityScope == JobEntityScope.ENTITY) == (entities != null)) {
            "an ENTITY-scoped job, and only one, names its entities"
        }
        val ticks = CronTicks(job.schedule)
        require(retention >= ticks.longestInterval(clock.instant()).plus(job.tolerance)) {
            "retention must cover the job's schedule interval plus its tolerance"
        }
        require(jobs.putIfAbsent(job.name, Registered(job, body, entities, ticks)) == null) { "job already registered" }
    }

    /** The registered jobs, by name. */
    fun registered(): List<JobDefinition> = jobs.values.map { it.job }.sortedBy { it.name }

    /** Emits `JobRegistered` once per registered job; called when the service starts (INV-REQ-1226 b6, INV-Q-2981). */
    fun announce() {
        val correlation = CorrelationId("job-registration:${UUID.randomUUID()}")
        scope.bind(TenantId.PLATFORM) {
            scope.transactions.inTransaction {
                outbox.append(
                    jobs.values.sortedBy { it.job.name }.map {
                        OutboundEvent(JobPayloads.JOB_REGISTERED, JobPayloads.jobRegistered(it.job))
                    },
                    context(JobTarget.Whole, "$service/jobs", correlation, CausationId(correlation.value)),
                )
            }
        }
    }

    /**
     * One scheduled firing of [jobName] at [at] (normally now): for each target (the whole job, or each entity
     * of an ENTITY-scoped job) a paused target is skipped, then the instance that takes the lock claims the
     * tick's `job_run` row and runs the body. Every instance may call this at the same firing.
     */
    fun fire(
        jobName: String,
        at: Instant = clock.instant(),
    ): Map<JobTarget, FireResult> {
        val registered = jobs[jobName] ?: throw IllegalArgumentException("job not registered")
        val tick = registered.ticks.tickContaining(at)
        val targets = registered.entities?.entities(registered.job) ?: listOf(JobTarget.Whole)
        return targets.associateWith { target ->
            if (isPaused(registered.job, target, at, endLapsed = true)) {
                FireResult.SKIPPED_PAUSED
            } else {
                election.runIfLeader(registered.job, target) {
                    val key = RunKey(registered.job, target, tick, JobTrigger.SCHEDULED, 1)
                    val claimed = inTenant(target) { state.begin(key, clock.instant()) }
                    if (claimed) {
                        execute(registered, key, newCorrelation(), null)
                        FireResult.RAN
                    } else {
                        FireResult.SKIPPED_TICK_TAKEN
                    }
                } ?: FireResult.SKIPPED_NOT_LEADER
            }
        }
    }

    /**
     * Records FAILED("abandoned") for each run of this service still in progress after its job's
     * lockAtMostFor: its instance died (INV-Q-2948). Returns the number swept.
     */
    fun sweepAbandoned(): Int {
        val now = clock.instant()
        return abandonedCandidates().count { candidate ->
            val registered = jobs[candidate.job] ?: return@count false
            if (!candidate.startedAt.plus(registered.job.lockAtMostFor).isBefore(now)) return@count false
            val key = RunKey(registered.job, candidate.target, candidate.tick, candidate.trigger, candidate.attempt)
            finish(registered, key, ABANDONED, Duration.between(candidate.startedAt, now).toMillis(), newCorrelation())
        }
    }

    /**
     * Deletes `job_run` rows that started, and `job_pause` rows that ended or ran out, more than [retention]
     * ago (T30 slot answer retention). Returns the number of rows deleted.
     */
    fun purge(): Int {
        val cutoff = Timestamp.from(clock.instant().minus(retention))
        return worker.connection.use { connection ->
            val runs =
                connection.prepareStatement(
                    "delete from $schemaName.${JobSchema.JOB_RUN} " +
                        "where service = ? and status <> 'IN_PROGRESS' and started_at < ?",
                ).use {
                    it.setString(1, service)
                    it.setTimestamp(2, cutoff)
                    it.executeUpdate()
                }
            val pauses =
                connection.prepareStatement(
                    "delete from $schemaName.${JobSchema.JOB_PAUSE} " +
                        "where service = ? and coalesce(ended_at, paused_until) < ?",
                ).use {
                    it.setString(1, service)
                    it.setTimestamp(2, cutoff)
                    it.executeUpdate()
                }
            runs + pauses
        }
    }

    // --- commands (INV-REQ-1138, INV-API-3023, INV-API-3025) ---

    override fun runNow(request: JobCommandRequest): JobCommandOutcome<JobRunState> {
        val registered = jobs[request.job] ?: return JobCommandOutcome.Refused(JobErrorCode.JOB_UNKNOWN)
        val target = authorise(registered.job, request, JobCommand.RUN_NOW) ?: return REFUSED_SCOPE
        val now = clock.instant()
        val paused = runNowOnPausedScope(registered, target, request.actor, now) ?: return REFUSED_SCOPE
        val tick = registered.ticks.tickContaining(now)
        val started: Pair<JobRunState, Boolean> =
            inTenant(target) {
                var outcome: Pair<JobRunState, Boolean>? = null
                while (outcome == null) {
                    val latest = state.latest(registered.job, target, tick, JobTrigger.MANUAL)
                    if (latest != null && latest.status != JobRunStatus.FAILED) {
                        outcome = runState(registered.job, target, tick, latest, repeat = true) to false
                    } else {
                        val attempt = (latest?.attempt ?: 0) + 1
                        if (state.begin(RunKey(registered.job, target, tick, JobTrigger.MANUAL, attempt), now)) {
                            audit.record(
                                auditEntry(request, JobCommand.RUN_NOW, registered.job, target, paused.priorState()),
                                context(target, aggregateKey(registered.job), request.correlation, request.causation),
                            )
                            val started = RunRow(attempt, JobRunStatus.IN_PROGRESS, now)
                            outcome = runState(registered.job, target, tick, started, repeat = false) to true
                        }
                    }
                }
                val done = checkNotNull(outcome)
                request.journal?.withinTransaction(JobCommandOutcome.Done(done.first, done.second))
                done
            }
        if (started.second) {
            val key = RunKey(registered.job, target, tick, JobTrigger.MANUAL, started.first.attempt)
            val skipped = paused.skipped()
            runNowExecutor.execute {
                execute(registered, key, request.correlation, request.causation) { tenant, entity ->
                    (tenant to entity) in skipped
                }
            }
        }
        return JobCommandOutcome.Done(started.first, created = started.second)
    }

    override fun pause(
        request: JobCommandRequest,
        until: Instant,
    ): JobCommandOutcome<JobPauseState> {
        val registered = jobs[request.job] ?: return JobCommandOutcome.Refused(JobErrorCode.JOB_UNKNOWN)
        val job = registered.job
        val target = authorise(job, request, JobCommand.PAUSE) ?: return REFUSED_SCOPE
        val now = clock.instant()
        if (!until.isAfter(now)) return JobCommandOutcome.Refused(JobErrorCode.PAUSE_END_NOT_FUTURE)
        val bound = deadlineBound(job, target)
        if (bound != null && until.isAfter(bound)) return JobCommandOutcome.Refused(JobErrorCode.PAUSE_PAST_DEADLINE)
        return inTenant(target) {
            var outcome: JobCommandOutcome<JobPauseState>? = null
            while (outcome == null) {
                val open = livePause(job, target, now, bound)
                outcome =
                    when {
                        open == null && state.openNewPause(job, target, now, until) -> {
                            recordAudit(request, JobCommand.PAUSE, job, target, """{"state":"ACTIVE"}""")
                            JobCommandOutcome.Done(JobPauseState(job.name, target, true, until, now), created = true)
                        }

                        open == null -> null

                        // a concurrent pause opened first: read it again

                        open.until == until ->
                            JobCommandOutcome.Done(JobPauseState(job.name, target, true, until, open.pausedAt), created = false)

                        else -> {
                            state.replaceUntil(job, target, until)
                            recordAudit(request, JobCommand.PAUSE, job, target, pausedJson(open))
                            val replaced = JobPauseState(job.name, target, true, until, open.pausedAt, open.until)
                            JobCommandOutcome.Done(replaced, created = true)
                        }
                    }
            }
            journal(request, checkNotNull(outcome))
        }
    }

    override fun resume(request: JobCommandRequest): JobCommandOutcome<JobPauseState> {
        val registered = jobs[request.job] ?: return JobCommandOutcome.Refused(JobErrorCode.JOB_UNKNOWN)
        val job = registered.job
        val target = authorise(job, request, JobCommand.RESUME) ?: return REFUSED_SCOPE
        val now = clock.instant()
        val bound = deadlineBound(job, target)
        return inTenant(target) {
            val open = livePause(job, target, now, bound)
            val outcome =
                if (open == null) {
                    JobCommandOutcome.Done(JobPauseState(job.name, target, false), created = false)
                } else {
                    state.endPause(job, target, now)
                    recordAudit(request, JobCommand.RESUME, job, target, pausedJson(open))
                    JobCommandOutcome.Done(JobPauseState(job.name, target, false), created = true)
                }
            journal(request, outcome)
        }
    }

    /** Hands a done outcome to the request's journal inside the command transaction (ARC-05, TPL-04). */
    private fun <T> journal(
        request: JobCommandRequest,
        outcome: JobCommandOutcome<T>,
    ): JobCommandOutcome<T> {
        if (outcome is JobCommandOutcome.Done) request.journal?.withinTransaction(outcome)
        return outcome
    }

    // --- internals ---

    /** The pauses in force on a run-now's scope, as the run handles them. */
    private class PausedScope(val pauses: List<Pair<TenantId, PauseInForce>>) {
        fun priorState(): String? = if (pauses.isEmpty()) null else JobPayloads.runNowPriorState(pauses.map { it.second })

        fun skipped(): Set<Pair<TenantId, EntityRef>> = pauses.filter { it.second.skipped }.mapNotNull { (tenant, pause) -> pause.entity?.let { tenant to it } }.toSet()
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
    private fun runNowOnPausedScope(
        registered: Registered,
        target: JobTarget,
        actor: String,
        now: Instant,
    ): PausedScope? {
        val job = registered.job
        val pauses = mutableListOf<Pair<TenantId, PauseInForce>>()
        val wide = inTenant(JobTarget.Whole) { peekPause(job, JobTarget.Whole, now, deadlineBound(job, JobTarget.Whole)) }
        if (wide != null) {
            if (!authorizer.isPlatformOperator(actor, JobCommand.RESUME)) return null
            pauses += TenantId.PLATFORM to PauseInForce(null, wide.until, wide.pausedAt, skipped = false)
        }
        val shares =
            when (target) {
                is JobTarget.Entity -> listOf(target)
                JobTarget.Whole -> registered.entities?.entities(job).orEmpty()
            }
        for (share in shares) {
            val own = inTenant(share) { peekPause(job, share, now, deadlineBound(job, share)) } ?: continue
            val liftable = authorizer.mayCommandEntity(actor, share.tenant, share.entity, JobCommand.RESUME)
            if (!liftable && target is JobTarget.Entity) return null
            pauses += share.tenant to PauseInForce(share.entity, own.until, own.pausedAt, skipped = !liftable)
        }
        // INV-Q-3567: the union of the unliftable pauses covering every item of the scope refuses it.
        if (shares.isNotEmpty() && pauses.count { it.second.skipped } == shares.size) return null
        return PausedScope(pauses)
    }

    /**
     * The command's target, or null when the actor may not command the job at that scope (JOB-002): an entity's
     * share of an ENTITY-scoped job needs the entity grant; the whole job, and any command on a PLATFORM-scoped
     * job with or without an entity, the platform operator role (INV-Q-2985, INV-Q-3032, INV-Q-3537). The
     * platform operator giving an entity for a PLATFORM-scoped job is a contract violation (400).
     */
    private fun authorise(
        job: JobDefinition,
        request: JobCommandRequest,
        command: JobCommand,
    ): JobTarget? {
        val entity = request.entity
        if (entity == null || job.entityScope == JobEntityScope.PLATFORM) {
            if (!authorizer.isPlatformOperator(request.actor, command)) return null
            require(entity == null) { "a PLATFORM-scoped job has no entity share" }
            return JobTarget.Whole
        }
        val target = JobTarget.Entity(scope.tenants.current(), entity)
        return if (authorizer.mayCommandEntity(request.actor, target.tenant, target.entity, command)) target else null
    }

    /**
     * The latest end of a pause of a deadline-critical job: the nearest due date minus the safety margin
     * (INV-Q-2946).
     */
    private fun deadlineBound(
        job: JobDefinition,
        target: JobTarget,
    ): Instant? {
        if (job.criticality != JobCriticality.DEADLINE_CRITICAL) return null
        return deadlines.nearestDueDate(job, target)?.minus(deadlines.safetyMargin(job, target))
    }

    /**
     * The open pause of (job, target) that still holds at [now], in the target's transaction. A pause past its
     * end time, or lapsed at the deadline bound, is ended where it ended and does not hold (INV-Q-2946).
     */
    private fun livePause(
        job: JobDefinition,
        target: JobTarget,
        now: Instant,
        bound: Instant?,
    ): PauseRow? {
        val open = state.openPause(job, target) ?: return null
        val end = if (bound != null && bound.isBefore(open.until)) bound else open.until
        if (now.isBefore(end)) return open
        state.endPause(job, target, maxOf(end, open.pausedAt))
        return null
    }

    /**
     * Whether a firing for [target] skips: its own pause, lapsing at its deadline bound, or for an entity the
     * job-wide pause, lapsing at the whole job's bound (the earliest over every entity, INV-Q-3531); both are
     * re-checked at every firing because due dates move (INV-REQ-1138 b2-b3, INV-Q-2946).
     */
    private fun isPaused(
        job: JobDefinition,
        target: JobTarget,
        at: Instant,
        endLapsed: Boolean,
    ): Boolean {
        val bound = deadlineBound(job, target)
        val own =
            inTenant(target) {
                if (endLapsed) livePause(job, target, at, bound) else peekPause(job, target, at, bound)
            }
        if (own != null) return true
        if (target is JobTarget.Entity) {
            val wideBound = deadlineBound(job, JobTarget.Whole)
            val wide =
                inTenant(JobTarget.Whole) {
                    if (endLapsed) {
                        livePause(job, JobTarget.Whole, at, wideBound)
                    } else {
                        peekPause(job, JobTarget.Whole, at, wideBound)
                    }
                }
            if (wide != null) return true
        }
        return false
    }

    private fun peekPause(
        job: JobDefinition,
        target: JobTarget,
        at: Instant,
        bound: Instant?,
    ): PauseRow? = state.openPause(job, target)?.takeIf {
        at.isBefore(if (bound != null && bound.isBefore(it.until)) bound else it.until)
    }

    /**
     * Runs the body and records the run. A scheduled firing skips paused entities' items; a run-now passes
     * [skips], the entities under pauses its caller cannot lift (INV-Q-3563).
     */
    private fun execute(
        registered: Registered,
        key: RunKey,
        correlation: CorrelationId,
        causation: CausationId?,
        skips: ((TenantId, EntityRef) -> Boolean)? = null,
    ) {
        val started = clock.instant()
        val context =
            JobRunContext(registered.job, key.target, key.trigger, key.tick, key.attempt) { tenant, entity ->
                if (skips != null) {
                    skips(tenant, entity)
                } else {
                    val target = if (tenant.isPlatform) JobTarget.Whole else JobTarget.Entity(tenant, entity)
                    isPaused(registered.job, target, clock.instant(), endLapsed = false)
                }
            }
        val failure =
            try {
                registered.body.run(context)
                null
            } catch (failed: Exception) {
                failed.javaClass.name // CS-34: the class, never the message
            }
        val millis = Duration.between(started, clock.instant()).toMillis().coerceAtLeast(0)
        finish(registered, key, failure, millis, correlation, causation)
    }

    /** Completes the run and writes its `JobRunRecorded` in one transaction; false when it was already completed. */
    private fun finish(
        registered: Registered,
        key: RunKey,
        failure: String?,
        durationMillis: Long,
        correlation: CorrelationId,
        causation: CausationId? = null,
    ): Boolean = inTenant(key.target) {
        val now = clock.instant()
        val finished = state.finish(key, failure, now, durationMillis)
        if (finished) {
            val nextRunAt = registered.ticks.next(now)
            val payload = JobPayloads.jobRunRecorded(registered.job, key.target, failure, durationMillis, key.tick, nextRunAt)
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
        finished
    }

    private data class Candidate(
        val job: String,
        val target: JobTarget,
        val tick: Instant,
        val trigger: JobTrigger,
        val attempt: Int,
        val startedAt: Instant,
    )

    private fun abandonedCandidates(): List<Candidate> = worker.connection.use { connection ->
        connection.prepareStatement(
            """
            select tenant_id, job, entity_ref, tick, trigger, attempt, started_at from $schemaName.${JobSchema.JOB_RUN}
            where service = ? and status = 'IN_PROGRESS' order by started_at limit $SWEEP_BATCH
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, service)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val tenant = TenantId(rows.getObject(1, UUID::class.java))
                        val target =
                            if (tenant.isPlatform) JobTarget.Whole else JobTarget.Entity(tenant, EntityRef(rows.getString(3)))
                        add(
                            Candidate(
                                rows.getString(2),
                                target,
                                rows.getTimestamp(4).toInstant(),
                                JobTrigger.valueOf(rows.getString(5)),
                                rows.getInt(6),
                                rows.getTimestamp(7).toInstant(),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun recordAudit(
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
        scope = when (target) {
            JobTarget.Whole -> aggregateKey(job)
            is JobTarget.Entity -> "${aggregateKey(job)}/${target.entity}"
        },
        reason = "Idempotency-Key ${request.idempotencyKey}" + (request.reason?.let { ": $it" } ?: ""),
        priorStateJson = priorState,
    )

    private fun pausedJson(open: PauseRow) = """{"state":"PAUSED","until":"${open.until}","pausedAt":"${open.pausedAt}"}"""

    private fun runState(
        job: JobDefinition,
        target: JobTarget,
        tick: Instant,
        row: RunRow,
        repeat: Boolean,
    ) = JobRunState(job.name, target, JobTrigger.MANUAL, tick, row.attempt, row.status, row.startedAt, repeat)

    private fun aggregateKey(job: JobDefinition) = "$service/${job.name}"

    private fun context(
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

    private fun <T> inTenant(
        target: JobTarget,
        block: () -> T,
    ): T = scope.bind(target.tenant) { scope.transactions.inTransaction(block) }

    private fun newCorrelation() = CorrelationId("job-run:${UUID.randomUUID()}")

    companion object {
        val DEFAULT_RETENTION: Duration = Duration.ofDays(30)
        const val ABANDONED = "abandoned"
        private const val SWEEP_BATCH = 500
        private val REFUSED_SCOPE = JobCommandOutcome.Refused(JobErrorCode.SCOPE_NOT_PERMITTED)
    }
}
