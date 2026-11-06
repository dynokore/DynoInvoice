package com.dynokore.gst.controlPlane.application.accreditation

import com.dynokore.gst.controlPlane.application.pack.Refusal
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.domain.accreditation.AccreditationTask
import com.dynokore.gst.controlPlane.domain.accreditation.TaskReason
import com.dynokore.gst.controlPlane.domain.accreditation.TaskStatus
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.operation.DeclaredOperation
import com.dynokore.platform.serviceruntime.operation.OperationAudit
import com.dynokore.platform.serviceruntime.operation.Reversibility
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.Instant

/** The position after which a page of tasks continues: the sort key (`opened_at` descending, `task_ref` ascending). */
data class TaskCursorKey(
    val openedAt: Instant,
    val taskRef: String,
)

/** The accreditation tasks (INV-REQ-1365). The service never deletes a row. */
interface AccreditationTaskRepository {
    fun find(taskRef: String): AccreditationTask?

    fun findFor(
        releaseRef: String,
        reason: TaskReason,
    ): AccreditationTask?

    /** Stores [task]; false when its (`release_ref`, `reason`) is already taken (nothing is stored). */
    fun insert(task: AccreditationTask): Boolean

    /** At most [limit] tasks in the sort order, after [after] when given, optionally of one [status]. */
    fun page(
        status: TaskStatus?,
        after: TaskCursorKey?,
        limit: Int,
    ): List<AccreditationTask>

    /** Sets a task OPEN to CONCLUDED; returns the stored task, or null when there is none. */
    fun conclude(
        taskRef: String,
        at: Instant,
        conclusion: String,
    ): AccreditationTask?
}

/** The outcome of a read that is guarded by a permission. */
sealed interface TaskRead<out T> {
    data object Denied : TaskRead<Nothing>

    data object Missing : TaskRead<Nothing>

    data class Found<T>(
        val value: T,
    ) : TaskRead<T>
}

/** One page of tasks: [more] is true when a task follows the last one. */
data class TaskPage(
    val items: List<AccreditationTask>,
    val more: Boolean,
)

/** listAccreditationTasks and getAccreditationTask: reads that gate nothing (INV-REQ-1365). */
class AccreditationTaskQueries(
    private val tasks: AccreditationTaskRepository,
    private val permissions: OperatorPermissions,
    private val transactions: TransactionRunner,
) {
    /** True when [actor] may list; the caller checks this before it reads or validates any input (ASVS V4). */
    fun mayList(actor: String): Boolean = permissions.holds(actor, AccreditationPermission.TASK_LIST)

    /** [limit] is 1 to [MAX_LIMIT]; the caller refuses anything else with 400. */
    fun list(
        actor: String,
        status: TaskStatus?,
        after: TaskCursorKey?,
        limit: Int,
    ): TaskRead<TaskPage> {
        require(limit in 1..MAX_LIMIT) { "limit is 1 to $MAX_LIMIT" }
        if (!permissions.holds(actor, AccreditationPermission.TASK_LIST)) return TaskRead.Denied
        val rows = transactions.inTransaction { tasks.page(status, after, limit + 1) }
        return TaskRead.Found(TaskPage(rows.take(limit), rows.size > limit))
    }

    fun get(
        actor: String,
        taskRef: String,
    ): TaskRead<AccreditationTask> =
        when {
            !permissions.holds(actor, AccreditationPermission.TASK_VIEW) -> TaskRead.Denied
            else -> transactions.inTransaction { tasks.find(taskRef) }?.let { TaskRead.Found(it) } ?: TaskRead.Missing
        }

    companion object {
        const val MAX_LIMIT = 500
        const val DEFAULT_LIMIT = 100
    }
}

data class ConcludeAccreditationTaskCommand(
    val actor: ActorId,
    val taskRef: String,
    val conclusion: String,
    val request: RequestContext,
) {
    init {
        require(
            taskRef.length in 1..AccreditationTask.MAX_REF,
        ) { "taskRef is 1..${AccreditationTask.MAX_REF} characters" }
        require(conclusion.length in 1..AccreditationTask.MAX_CONCLUSION) {
            "conclusion is 1..${AccreditationTask.MAX_CONCLUSION} characters"
        }
    }
}

/**
 * concludeAccreditationTask (INV-REQ-1365): a holder of [AccreditationPermission.CONCLUDE] sets an OPEN task
 * CONCLUDED with the injected clock's instant and the conclusion. A repeat with the same conclusion reports the
 * existing task; a different conclusion on a CONCLUDED task is refused ([Refusal.TASK_ALREADY_CONCLUDED]). The
 * conclusion never gates a release.
 */
class ConcludeAccreditationTask(
    private val tasks: AccreditationTaskRepository,
    private val permissions: OperatorPermissions,
    private val clock: Clock,
) : DeclaredOperation<ConcludeAccreditationTaskCommand, AccreditationTask?, AccreditationTask> {
    override val operationId = "concludeAccreditationTask"
    override val reversibility = Reversibility.IRREVERSIBLE
    override val audited = true

    override fun permits(
        actor: String,
        command: ConcludeAccreditationTaskCommand,
    ) = permissions.holds(actor, AccreditationPermission.CONCLUDE) && actor == command.actor.value

    override fun repeatKey(command: ConcludeAccreditationTaskCommand) = command.taskRef

    override fun load(command: ConcludeAccreditationTaskCommand) = tasks.find(command.taskRef)

    override fun priorResult(
        command: ConcludeAccreditationTaskCommand,
        state: AccreditationTask?,
    ) = state?.takeIf { it.status == TaskStatus.CONCLUDED && it.conclusion == command.conclusion }

    override fun unmetPrecondition(
        command: ConcludeAccreditationTaskCommand,
        state: AccreditationTask?,
    ): String? =
        when {
            state == null -> Refusal(Refusal.TASK_NOT_FOUND, command.taskRef).encode()
            state.status == TaskStatus.CONCLUDED -> Refusal(Refusal.TASK_ALREADY_CONCLUDED, command.taskRef).encode()
            else -> null
        }

    override fun perform(
        command: ConcludeAccreditationTaskCommand,
        state: AccreditationTask?,
    ): AccreditationTask =
        checkNotNull(tasks.conclude(command.taskRef, clock.instant(), command.conclusion)) {
            "concludeAccreditationTask performed on a task that is gone"
        }

    override fun audit(
        command: ConcludeAccreditationTaskCommand,
        state: AccreditationTask?,
        result: AccreditationTask,
    ) = OperationAudit(
        "platform",
        null,
        null,
        EventContext(
            tenant = TenantId.PLATFORM,
            entity = EntityRef.PLATFORM,
            docUuid = null,
            transactionDate = null,
            aggregateKey = command.taskRef,
            aggregateVersion = null,
            correlation = CorrelationId(command.request.correlationId),
            causation = CausationId(command.request.causationId),
        ),
    )
}
