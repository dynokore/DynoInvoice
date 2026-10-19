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

/**
 * A [JobCommandJournal] that also opens the command's transaction: [JobRuntime] calls [begin] as the first step
 * of a run-now, pause or resume transaction, before any effect, and [withinTransaction] at its end. The API-15
 * store takes its in-flight guard and looks the key up there, in the command's one transaction on one
 * connection (ARC-05, TPL-04; coordinator ruling INV-Q-3945).
 */
interface TransactionalJobCommandJournal : JobCommandJournal {
    fun begin()
}

/**
 * The job contract of a service (INV-REQ-1136, INV-REQ-1138, INV-REQ-2405): registration with
 * `JobRegistered`, scheduled firings under ShedLock leader election with one `job_run` row and one
 * `JobRunRecorded` per run, run now / pause / resume, the abandoned-run sweeper and the retention purge.
 *
 * The service keeps its own scheduler (INV-REQ-1136 b2): its `@Scheduled` method calls [fire] at each
 * firing on every instance; the lock and the `job_run` key make one run per tick. Wiring:
 * - [scope]: the service's tenant binding and transactions, on the runtime login;
 * - [dsl]: the DSLContext of tenancy-jooq's factory, which every statement of the job state goes through;
 * - leader election: ShedLock over the service's `shedlock` table (no tenant data) through [dsl] and [scope];
 * - [outbox] and [audit]: the service's outbox writer and audit writer (INV-REQ-1226, INV-REQ-2588).
 *
 * Actors (owner ruling INV-Q-4344 (b), coordinator ruling INV-Q-4268): a job's runs (the firing, the claim of its
 * tick, its completion, the sweep of an abandoned run) bind `svc:<svc>/job:<job>` ([WorkloadPrincipal.job]); the
 * announce, the sweeper's read and the purge bind the service's `svc:<svc>`; an operator's command binds the
 * operator's `sub`. The sweeper and the purge reach across tenants only through the `<svc>_sys`-owned
 * [JobSchema.IN_PROGRESS_VIEW] and [JobSchema.PURGE_EXPIRED] (coordinator ruling INV-Q-4346 (A)); [checkRetention]
 * refuses a configured [retention] that differs from the migration-written retention row.
 */
@Suppress("LongParameterList") // constructor injection under CS-05; INV-Q-4629 (CS-50)
class JobRuntime(
    private val service: String,
    schema: String,
    private val scope: TenantScope,
    private val dsl: DSLContext,
    outbox: OutboxWriter,
    audit: AuditWriter,
    authorizer: JobCommandAuthorizer,
    deadlines: DeadlineSource,
    private val clock: Clock,
    runNowExecutor: Executor,
    /** job_run and ended job_pause rows are purged after this window (T30 slot answer retention: default 30 days). */
    val retention: Duration = DEFAULT_RETENTION,
) : JobCommands {
    private val principal = WorkloadPrincipal(service)
    private val state = JdbcJobState(dsl, schema, service)
    private val election = ShedLockLeaderElection.postgres(dsl, scope, schema, clock)

    init {
        require(scope.workloadPrincipal == principal.value) {
            "the TenantScope's workload principal must be the service's own (${principal.value})"
        }
    }

    private val jobs = ConcurrentHashMap<String, RegisteredJob>()
    private val scopes = JobScopes(scope, principal)
    private val pauses = JobPauseRules(scope, scopes, state, authorizer, deadlines)
    private val core = JobCore(clock, state, scopes, JobRecords(service, scope, outbox, audit), pauses)
    private val runs = JobRunBody(core, election)
    private val retentionRule =
        JobRetention(
            scope,
            dsl,
            DSL.name(SqlIdentifier.require(schema, "schema"), JobSchema.RETENTION_WINDOW),
            DSL.name(SqlIdentifier.require(schema, "schema"), JobSchema.PURGE_EXPIRED),
            retention,
        )
    private val sweeper =
        JobSweeper(
            scope,
            dsl,
            DSL.name(SqlIdentifier.require(schema, "schema"), JobSchema.IN_PROGRESS_RUNS),
            jobs,
            core,
            runs,
        )
    private val runNowCommand = JobRunNowCommand(jobs, core, runs, runNowExecutor)
    private val pauseCommands = JobPauseCommands(jobs, core)

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
        require(jobs.putIfAbsent(job.name, RegisteredJob(job, body, entities, ticks)) == null) {
            "job already registered"
        }
    }

    /** The registered jobs, by name. */
    override fun registered(): List<JobDefinition> = jobs.values.map { it.job }.sortedBy { it.name }

    /**
     * The job runtime's start, called when the service starts: refuses to start ([checkRetention]) when the configured
     * retention differs from the migration-written row (INV-Q-4346 (A)), then emits `JobRegistered` once per
     * registered job (INV-REQ-1226 b6, INV-Q-2981).
     */
    fun announce() {
        checkRetention()
        core.records.announceRegistered(jobs.values)
    }

    /**
     * One scheduled firing of [jobName] at [at] (normally now): for each target (the whole job, or each entity
     * of an ENTITY-scoped job) a paused target is skipped, then the instance that takes the lock claims the
     * tick's `job_run` row and runs the body. Every instance may call this at the same firing. A firing runs in the
     * pool's background lane (INV-REQ-1230 b1).
     */
    fun fire(
        jobName: String,
        at: Instant = clock.instant(),
    ): Map<JobTarget, FireResult> =
        PoolLanes.inLane(PoolLane.BACKGROUND) {
            runs.fire(jobs[jobName] ?: throw IllegalArgumentException("job not registered"), at)
        }

    /**
     * Records FAILED("abandoned") for each run of this service still in progress after its job's
     * lockAtMostFor: its instance died (INV-Q-2948). Returns the number swept.
     */
    fun sweepAbandoned(): Int = sweeper.sweep()

    /**
     * Refuses to start when [retention] differs from the job-retention row the migration runner wrote (coordinator
     * ruling INV-Q-4346 (A)), or when the row is missing: the purge reads only the row.
     */
    fun checkRetention() = retentionRule.check()

    /**
     * Deletes `job_run` rows that started, and `job_pause` rows that ended or ran out, before the migration-written
     * retention window (T30 slot answer retention), through the `<svc>_sys` purge function in batches, each its own
     * transaction under the service's workload principal. Returns the number of rows deleted; with no retention row
     * nothing is deleted and an error is logged.
     */
    fun purge(): Int = retentionRule.purge()

    // --- commands (INV-REQ-1138, INV-API-3023, INV-API-3025) ---

    override fun runNow(request: JobCommandRequest): JobCommandOutcome<JobRunState> = runNowCommand.runNow(request)

    override fun pause(
        request: JobCommandRequest,
        until: Instant,
    ): JobCommandOutcome<JobPauseState> = pauseCommands.pause(request, until)

    override fun resume(request: JobCommandRequest): JobCommandOutcome<JobPauseState> = pauseCommands.resume(request)

    companion object {
        val DEFAULT_RETENTION: Duration = Duration.ofDays(30)
        const val ABANDONED = "abandoned"
    }
}
