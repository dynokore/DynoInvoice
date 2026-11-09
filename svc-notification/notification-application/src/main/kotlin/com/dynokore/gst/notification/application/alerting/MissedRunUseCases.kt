package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.domain.alerting.JobExpectation
import com.dynokore.gst.notification.domain.alerting.JobKey
import com.dynokore.gst.notification.domain.alerting.MissedRunAlert
import com.dynokore.gst.notification.domain.alerting.MissedRunAlertDraft
import com.dynokore.gst.notification.domain.alerting.MissedRunDecision
import com.dynokore.gst.notification.domain.alerting.MissedRunRules
import com.dynokore.gst.notification.domain.alerting.MissedRunWithheld
import com.dynokore.gst.notification.domain.alerting.ROLE_DEPLOYMENT_OPERATOR
import com.dynokore.gst.notification.domain.alerting.RecordedRun
import java.time.Duration
import java.time.Instant

/** The expectation of each job (INV-REQ-1139); one per [JobKey]. */
interface JobExpectationStore {
    fun get(key: JobKey): JobExpectation?

    fun put(expectation: JobExpectation)

    fun all(): List<JobExpectation>
}

/** Raises one platform alert of class MISSED_RUN (INV-Q-15933 A). */
interface PlatformAlertSink {
    fun raise(draft: MissedRunAlertDraft)
}

/** The references of the missed-run alerts already raised. */
interface AlertedRefs {
    fun all(): Set<String>
}

/** The default recipients of a role (routing target only, never a permission check). */
interface DefaultRecipients {
    fun forRole(role: String): List<String>
}

private fun text(
    payload: Map<String, Any?>,
    name: String,
): String {
    val value = payload[name] as? String
    if (value.isNullOrBlank()) throw ContractViolation("$name is missing or not text")
    return value
}

private fun instant(
    payload: Map<String, Any?>,
    name: String,
): Instant =
    try {
        Instant.parse(text(payload, name))
    } catch (e: java.time.format.DateTimeParseException) {
        throw ContractViolation("$name is not an instant: ${e.message}")
    }

private fun duration(
    payload: Map<String, Any?>,
    name: String,
): Duration =
    try {
        Duration.parse(text(payload, name))
    } catch (e: java.time.format.DateTimeParseException) {
        throw ContractViolation("$name is not an ISO-8601 duration: ${e.message}")
    }

/** Wraps a domain precondition failure of the payload values as a contract violation. */
private inline fun <T> contract(block: () -> T): T =
    try {
        block()
    } catch (expected: IllegalArgumentException) {
        throw ContractViolation(expected.message ?: "invalid value")
    }

sealed class HandleJobRegisteredResult(
    val kind: String,
) {
    data object Ignored : HandleJobRegisteredResult("IGNORED")

    data class Registered(
        val expectation: JobExpectation,
    ) : HandleJobRegisteredResult("REGISTERED")
}

/** Handles JobRegistered (INV-REQ-1139 behavior 5; INV-Q-15930 A). Fails closed on a malformed payload. */
class HandleJobRegistered(
    private val processed: ProcessedEvents,
    private val store: JobExpectationStore,
) {
    fun handle(
        eventId: String,
        payload: Map<String, Any?>,
    ): HandleJobRegisteredResult {
        if (!processed.firstTime(eventId)) return HandleJobRegisteredResult.Ignored
        val job = text(payload, "job")
        val owner = text(payload, "ownerService")
        text(payload, "entityScope") // type check only: an ENTITY-scoped job is expected at platform level
        val tolerance = duration(payload, "tolerance")
        val nextRunAt = instant(payload, "nextRunAt")
        val expectation =
            contract {
                val key = JobKey(owner, job)
                MissedRunRules.register(store.get(key), key, nextRunAt, tolerance)
            }
        store.put(expectation)
        return HandleJobRegisteredResult.Registered(expectation)
    }
}

sealed class HandleJobRunRecordedResult(
    val kind: String,
) {
    data object Ignored : HandleJobRunRecordedResult("IGNORED")

    data object AwaitingRegistration : HandleJobRunRecordedResult("AWAITING_REGISTRATION")

    data class Recorded(
        val expectation: JobExpectation,
    ) : HandleJobRunRecordedResult("RECORDED")
}

/** Handles JobRunRecorded (INV-Q-15930 rules 1 and 3). The entity of the run is not used for matching. */
class HandleJobRunRecorded(
    private val processed: ProcessedEvents,
    private val store: JobExpectationStore,
) {
    fun handle(
        eventId: String,
        payload: Map<String, Any?>,
    ): HandleJobRunRecordedResult {
        if (!processed.firstTime(eventId)) return HandleJobRunRecordedResult.Ignored
        val job = text(payload, "job")
        val owner = text(payload, "ownerService")
        val scheduledFor = instant(payload, "scheduledFor")
        val nextRunAt = instant(payload, "nextRunAt")
        val tolerance = if (payload["tolerance"] == null) null else duration(payload, "tolerance")
        val updated =
            contract {
                val key = JobKey(owner, job)
                MissedRunRules.recordRun(store.get(key), RecordedRun(key, scheduledFor, nextRunAt), tolerance)
            }
        return updated?.let {
            store.put(it)
            HandleJobRunRecordedResult.Recorded(it)
        } ?: HandleJobRunRecordedResult.AwaitingRegistration
    }
}

/** The outcome of one sweep: alerts raised and alerts withheld, by reason. */
data class SweepMissedRunsResult(
    val raised: Int,
    val withheld: Map<MissedRunWithheld, Int>,
)

/** Raises one platform alert per missed run (INV-REQ-1139 behavior 2 and 3). */
class SweepMissedRuns(
    private val store: JobExpectationStore,
    private val recipients: DefaultRecipients,
    private val alerted: AlertedRefs,
    private val sink: PlatformAlertSink,
) {
    fun run(now: Instant): SweepMissedRunsResult {
        val missed = MissedRunRules.missed(store.all(), now)
        if (missed.isEmpty()) return SweepMissedRunsResult(0, emptyMap())
        val to = recipients.forRole(ROLE_DEPLOYMENT_OPERATOR)
        val refs = alerted.all()
        var raised = 0
        val withheld = linkedMapOf<MissedRunWithheld, Int>()
        for (m in missed) {
            when (val decision = MissedRunAlert.decide(m, to, refs)) {
                is MissedRunDecision.Raise -> {
                    sink.raise(decision.draft)
                    raised++
                }

                is MissedRunDecision.Withhold -> {
                    withheld.merge(decision.reason, 1, Int::plus)
                }
            }
        }
        return SweepMissedRunsResult(raised, withheld)
    }
}
