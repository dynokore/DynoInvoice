package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.operation.Reversibility
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.util.UUID

/**
 * The three job commands of the service-runtime operation contract (INV-REQ-1138 b4), with the reversibility
 * the contract's `x-operation` declares (INV-REQ-957 b6): a run-now is irreversible where the job transmits, a
 * pause is undone by a resume and a resume by a pause.
 */
enum class JobCommand(
    val operationId: String,
    val reversibility: Reversibility,
) {
    RUN_NOW("runJobNow", Reversibility.IRREVERSIBLE),
    PAUSE("pauseJob", Reversibility.UNDOABLE),
    RESUME("resumeJob", Reversibility.UNDOABLE),
}

/**
 * One command request. The actor comes from the propagated end-user token, never a field (CS-23,
 * INV-Q-2950); the entity's tenant is the bound tenant of the caller's session (ARC-21). [entity] absent
 * means the whole job or job-wide (INV-Q-2985, INV-Q-3032). [reason] is the caller's optional text
 * (max 500); the audit entry's reason records [idempotencyKey] plus that text (INV-REQ-2588 b5).
 */
data class JobCommandRequest(
    val actor: String,
    val job: String,
    val entity: EntityRef?,
    val idempotencyKey: UUID,
    val reason: String? = null,
    val correlation: CorrelationId,
    val causation: CausationId,
    val journal: JobCommandJournal? = null,
) {
    init {
        require(actor.isNotBlank()) { "actor must not be blank" }
        require(reason == null || (reason.isNotBlank() && reason.length <= MAX_REASON)) {
            "reason is 1 to $MAX_REASON characters"
        }
        require(entity == null || entity.value.length <= JobTarget.MAX_ENTITY_REF) { "entity reference too long" }
    }

    companion object {
        const val MAX_REASON = 500
    }
}

/**
 * Called by [JobCommands] inside the command's own transaction, after its state change and audit entry and before
 * it commits (ARC-05: one command, one transaction; TPL-04, API-15 S2 T1). The HTTP adapter stores its API-15
 * idempotency record here, so the record commits or rolls back with the effect. A refusal writes no state and does
 * not call it.
 */
fun interface JobCommandJournal {
    fun withinTransaction(outcome: JobCommandOutcome.Done<*>)
}

/**
 * Who may command a job at a scope. service-runtime enforces the split (INV-REQ-1138 b1-b2, INV-Q-2985,
 * INV-Q-3032): an entity's share of an ENTITY-scoped job needs [mayCommandEntity]; a whole-job run, a
 * job-wide pause or resume, and any command on a PLATFORM-scoped job need [isPlatformOperator]. The owning
 * service answers both against its authority replica (CS-23): which roles (`integration.engineer` on the
 * entity, `deployment.operator` in the private form; `platform.engineer` in SaaS) is product vocabulary,
 * kept out of L1 (ARC-16).
 */
interface JobCommandAuthorizer {
    fun mayCommandEntity(
        actor: String,
        tenant: TenantId,
        entity: EntityRef,
        command: JobCommand,
    ): Boolean

    fun isPlatformOperator(
        actor: String,
        command: JobCommand,
    ): Boolean
}

/** The catalogue codes of the job commands (platform-contracts gst-catalogue 1.5.0; INV-API-3023, INV-API-3025). */
private const val HTTP_NOT_FOUND = 404
private const val HTTP_FORBIDDEN = 403
private const val HTTP_UNPROCESSABLE = 422

enum class JobErrorCode(
    val code: String,
    val httpStatus: Int,
) {
    /** No job of that name is registered in the owning service. */
    JOB_UNKNOWN("JOB-001", HTTP_NOT_FOUND),

    /** The caller's role does not permit the command at the requested scope. */
    SCOPE_NOT_PERMITTED("JOB-002", HTTP_FORBIDDEN),

    /** The until of a deadline-critical pause passes the nearest due date minus the safety margin. */
    PAUSE_PAST_DEADLINE("JOB-003", HTTP_UNPROCESSABLE),

    /** The until is not later than the request time. */
    PAUSE_END_NOT_FUTURE("JOB-004", HTTP_UNPROCESSABLE),
}

/** A command's outcome: done with its state, or refused with a catalogue code and nothing changed (ARC-19). */
sealed interface JobCommandOutcome<out T> {
    /** [created] is true when this call started a run (202) or changed a pause; false for a repeat or no-op (200). */
    data class Done<T>(
        val state: T,
        val created: Boolean,
    ) : JobCommandOutcome<T>

    data class Refused(
        val code: JobErrorCode,
    ) : JobCommandOutcome<Nothing>
}

/** `JobPauseState` of the operation contract (INV-Q-3425 A): the key's own state. */
data class JobPauseState(
    val job: String,
    val target: JobTarget,
    val paused: Boolean,
    val until: Instant? = null,
    val pausedAt: Instant? = null,
    val previousUntil: Instant? = null,
)

/**
 * runJobNow, pauseJob and resumeJob (INV-REQ-1138), served by service-runtime in each owning service.
 * Each call that changes something writes an `AuditEntryRecorded` in its transaction (INV-Q-2950).
 */
interface JobCommands {
    /**
     * Runs the job once now (INV-REQ-1138 b1). Repeat key (job, entity or whole job, run window = the
     * schedule interval containing the request): a repeat returns the first run while in progress or
     * succeeded, with no second effect; after FAILED a new attempt starts (INV-Q-3034). Run-now and
     * scheduled runs are keyed apart (INV-Q-3039). The run continues after the call returns.
     */
    fun runNow(request: JobCommandRequest): JobCommandOutcome<JobRunState>

    /**
     * Pauses until [until] (INV-REQ-1138 b2-b3): later than now (JOB-004) and, for a deadline-critical job,
     * not past the nearest due date minus the safety margin (JOB-003). The same until has no second effect;
     * a different until replaces the active pause's end time (INV-Q-3037).
     */
    fun pause(
        request: JobCommandRequest,
        until: Instant,
    ): JobCommandOutcome<JobPauseState>

    /** Ends the key's active pause; a key that is not paused returns its state with no effect. */
    fun resume(request: JobCommandRequest): JobCommandOutcome<JobPauseState>

    /**
     * The registered jobs, by name: the HTTP side reads a job's entity scope to bind a tenantless caller only for a
     * platform-level target (coordinator ruling INV-Q-4115). None by default, so such a caller is refused.
     */
    fun registered(): List<JobDefinition> = emptyList()
}
