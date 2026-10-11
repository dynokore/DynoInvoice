package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Duration
import java.time.Instant

/**
 * What a job body is told about its run. [target] is the entity whose items the run processes, or
 * [JobTarget.Whole]; an entity run processes only that entity's items (INV-AC-3041). A whole run that
 * processes several entities' items asks [skips] per item (INV-REQ-1138 b1-b2).
 */
class JobRunContext(
    val job: JobDefinition,
    val target: JobTarget,
    val trigger: JobTrigger,
    val scheduledFor: Instant,
    val attempt: Int,
    private val pausedCheck: (TenantId, EntityRef) -> Boolean,
) {
    /**
     * True when this run skips that entity's items. A scheduled firing skips an entity paused now (its own pause
     * or a job-wide pause, within the deadline bound); a run-now overrides the pauses its caller can lift and
     * skips only the entities under pauses it cannot lift (INV-Q-3533, INV-Q-3563).
     */
    fun skips(
        tenant: TenantId,
        entity: EntityRef,
    ): Boolean = pausedCheck(tenant, entity)
}

/**
 * The job's work. The body claims its items in bounded batches and binds each item's tenant (TPL-09,
 * CS-33); a thrown exception is a FAILED run whose reason is the exception class, never its message (CS-34).
 */
fun interface JobBody {
    fun run(context: JobRunContext)
}

/**
 * The entities an ENTITY-scoped job serves at a firing: one run per entity, each with its own lock, run
 * row and `JobRunRecorded` (INV-REQ-2405 b4). Supplied by the owning service, which knows its entities.
 */
fun interface JobEntitySource {
    fun entities(job: JobDefinition): List<JobTarget.Entity>
}

/**
 * The deadlines of a deadline-critical job (INV-REQ-1138 b3, INV-Q-2946). Implemented by the owning
 * service: for `submission-svc` its obligation register plus the VP-18 safety margin read through
 * PolicyReader (bound in Wave 3). [FixedDeadlineSource] is the Wave 1 fake.
 */
interface DeadlineSource {
    /**
     * The nearest due date the job serves for [target], or `null` when none is pending. For [JobTarget.Whole]
     * (a job-wide pause, or a PLATFORM-scoped job) the service answers for the whole job: with [safetyMargin]
     * it gives the earliest "due date minus margin" over every entity the job serves (INV-Q-3531).
     */
    fun nearestDueDate(
        job: JobDefinition,
        target: JobTarget,
    ): Instant?

    /** The safety margin before that due date (VP-18 for `submission-svc`). */
    fun safetyMargin(
        job: JobDefinition,
        target: JobTarget,
    ): Duration
}

/** A [DeadlineSource] with fixed answers per target, for tests and services with no deadlines yet. */
class FixedDeadlineSource(
    private val dueDates: Map<JobTarget, Instant> = emptyMap(),
    private val margin: Duration = Duration.ZERO,
) : DeadlineSource {
    override fun nearestDueDate(
        job: JobDefinition,
        target: JobTarget,
    ): Instant? = dueDates[target]

    override fun safetyMargin(
        job: JobDefinition,
        target: JobTarget,
    ): Duration = margin
}

/** The state of a run (`JobRunState` of the service-runtime operation contract, INV-Q-3425 A). */
data class JobRunState(
    val job: String,
    val target: JobTarget,
    val trigger: JobTrigger,
    val scheduledFor: Instant,
    val attempt: Int,
    val status: JobRunStatus,
    val startedAt: Instant,
    val repeat: Boolean,
)

/** What one scheduled firing did for one target. */
enum class FireResult { RAN, SKIPPED_PAUSED, SKIPPED_NOT_LEADER, SKIPPED_TICK_TAKEN }
