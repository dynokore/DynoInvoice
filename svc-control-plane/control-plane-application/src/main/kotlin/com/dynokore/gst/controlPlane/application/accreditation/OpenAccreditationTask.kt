package com.dynokore.gst.controlPlane.application.accreditation

import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.domain.accreditation.AccreditationStatus
import com.dynokore.gst.controlPlane.domain.accreditation.AccreditationTask
import com.dynokore.gst.controlPlane.domain.accreditation.TaskReason
import com.dynokore.gst.controlPlane.domain.accreditation.TaskStatus
import com.dynokore.gst.controlPlane.domain.release.AccreditationCheck
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** The release a task is opened for: a train version or a pack reference. */
data class ReleaseTarget(
    val kind: Kind,
    val ref: String,
) {
    enum class Kind { TRAIN, PACK }
}

/** The accreditation check held by the release record of a [ReleaseTarget] (INV-T-7323). */
interface ReleaseAccreditationLinks {
    /** The stored check, or null when the release record does not exist. */
    fun current(target: ReleaseTarget): AccreditationCheck?

    /** Stores [check] in the release record of [target]. */
    fun link(
        target: ReleaseTarget,
        check: AccreditationCheck,
    )
}

/** What the AccreditationAlertRaised event carries (the payload schema of service-runtime). */
data class AccreditationAlert(
    val alertRef: String,
    val taskRef: String,
    val reason: TaskReason,
    val raisedAt: java.time.Instant,
    val releaseRef: String?,
    val currentUntil: LocalDate?,
)

/** Raises the alert: a platform event written to the outbox in the open transaction. */
fun interface AccreditationAlerts {
    fun raise(
        alert: AccreditationAlert,
        request: RequestContext,
    )
}

/** The collaborators of the accreditation step that touch data. */
data class AccreditationStepPorts(
    val tasks: AccreditationTaskRepository,
    val links: ReleaseAccreditationLinks,
    val alerts: AccreditationAlerts,
    val records: AccreditationRecords,
    val queue: AccreditationStepQueue,
)

/**
 * The accreditation step of the release pipeline (INV-REQ-1365, INV-REQ-2657 behavior 5, INV-AC-2340). It runs after
 * the release record is stored and committed, in a transaction of its own, and never inside the release transaction.
 * When the accreditation is LAPSED, or an assessment is requested, it opens one task, raises one alert and stores both
 * references in the release record. A failure is reported to [reporting] and changes nothing: it never rolls back or
 * delays the release, and a repeat of the release request runs the step again. NOT_RECORDED alone raises nothing.
 */
class OpenAccreditationTask(
    private val ports: AccreditationStepPorts,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val reporting: StepReporting,
    private val refs: (prefix: String) -> String = { "$it-${UUID.randomUUID()}" },
) {
    /** The check now stored for [target], or null when the step did not run to the end. */
    fun afterRelease(
        target: ReleaseTarget,
        assessmentRequested: Boolean,
        request: RequestContext,
    ): AccreditationCheck? = attempt(target) { ended(target, open(target, assessmentRequested, request)) }

    /**
     * One attempt of the relay for a pending row of [target]: it claims the row (skipping one another run holds or
     * that is not due), runs the step with the stored request and ends the work. A failure rolls the attempt back.
     */
    fun retry(target: ReleaseTarget) {
        attempt(target) {
            ports.queue.claim(target, clock.instant())?.let {
                ended(target, open(target, it.assessmentRequested, it.request))
            }
        }
    }

    /**
     * Runs [body] in a transaction of its own; [body] ends the work through [ended]. On a failure the transaction rolls back, the row stays, and a second small transaction
     * records the attempt; a failure of that bookkeeping is reported and changes nothing (the row stays due).
     */
    @Suppress("TooGenericExceptionCaught") // any failure of this step is reported and leaves the release untouched
    private fun attempt(
        target: ReleaseTarget,
        body: () -> AccreditationCheck?,
    ): AccreditationCheck? =
        try {
            transactions.inTransaction(body)
        } catch (failure: Exception) {
            reporting.failed(target, failure)
            bookkeep(target, failure)
            null
        }

    /** The work of [target] ended (a task opened, a task already linked, or no trigger): its pending row goes. */
    private fun ended(
        target: ReleaseTarget,
        check: AccreditationCheck?,
    ): AccreditationCheck? = check.also { ports.queue.delete(target) }

    @Suppress("TooGenericExceptionCaught")
    private fun bookkeep(
        target: ReleaseTarget,
        failure: Exception,
    ) {
        val error = failure.javaClass.simpleName
        try {
            val exhausted =
                transactions.inTransaction { ports.queue.recordFailure(target, error, clock.instant()) }
            if (exhausted) reporting.exhausted(target, error)
        } catch (bookkeeping: Exception) {
            reporting.bookkeepingFailed(target, bookkeeping)
        }
    }

    private fun open(
        target: ReleaseTarget,
        assessmentRequested: Boolean,
        request: RequestContext,
    ): AccreditationCheck? {
        val stored = ports.links.current(target)
        // no record, or a task already linked by an earlier run: nothing to open
        if (stored == null || stored.taskRef != null) return stored?.takeIf { it.taskRef != null }
        val latest = ports.records.latest()
        val status = AccreditationStatus.of(latest, LocalDate.now(clock.withZone(ZoneOffset.UTC)))
        val reason = TaskReason.of(status.currency, assessmentRequested)
        return reason?.let { openTask(target, it, assessmentRequested, latest?.currentUntil, request) } ?: stored
    }

    private fun openTask(
        target: ReleaseTarget,
        reason: TaskReason,
        assessmentRequested: Boolean,
        currentUntil: LocalDate?,
        request: RequestContext,
    ): AccreditationCheck {
        val now = clock.instant()
        val task =
            ports.tasks.findFor(target.ref, reason)
                ?: AccreditationTask(refs("TASK"), TaskStatus.OPEN, reason, now, refs("ALERT"), target.ref).also {
                    check(ports.tasks.insert(it)) { "the task of ${target.ref} $reason is already taken" }
                    ports.alerts.raise(
                        AccreditationAlert(it.alertRef, it.taskRef, reason, now, target.ref, currentUntil),
                        request,
                    )
                }
        return AccreditationCheck(assessmentRequested, task.alertRef, task.taskRef).also {
            ports.links.link(target, it)
        }
    }
}
