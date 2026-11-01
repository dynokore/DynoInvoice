package com.dynokore.gst.notification.domain.alerting

import java.time.Duration
import java.time.Instant

const val CLASS_MISSED_RUN = "MISSED_RUN"
const val TRIGGER_JOB_NOT_RUN = "JOB_NOT_RUN"

/** Default recipient role of a missed run; routing target only, never a permission check. */
const val ROLE_DEPLOYMENT_OPERATOR = "deployment.operator"

/** A job of a service. No entity: a run for any entity satisfies the expectation. */
data class JobKey(
    val ownerService: String,
    val job: String,
) {
    init {
        require(ownerService.isNotBlank()) { "ownerService must not be blank" }
        require(job.isNotBlank()) { "job must not be blank" }
    }
}

/** The next run a job owes: due at [expectedAt], missed once the clock is after [deadline]. */
data class JobExpectation(
    val key: JobKey,
    val expectedAt: Instant,
    val tolerance: Duration,
) {
    init {
        require(!tolerance.isNegative) { "tolerance must not be negative" }
    }

    val deadline: Instant get() = expectedAt.plus(tolerance)
}

/** A recorded run (any outcome: a FAILED run is a run) and the next run time its owner supplies. */
data class RecordedRun(
    val key: JobKey,
    val scheduledFor: Instant,
    val nextRunAt: Instant,
) {
    init {
        require(nextRunAt.isAfter(scheduledFor)) { "nextRunAt must be after scheduledFor" }
    }
}

/** Pure rules of the missed-run expectation. Read only their arguments; keep no state. */
object MissedRunRules {
    /** A registration never moves an unmet expectation, so a restart cannot hide a missed tick. */
    fun register(
        held: JobExpectation?,
        key: JobKey,
        nextRunAt: Instant,
        tolerance: Duration,
    ): JobExpectation = held?.copy(tolerance = tolerance) ?: JobExpectation(key, nextRunAt, tolerance)

    /** Null when the run waits for the registration (no expectation and no tolerance yet). */
    fun recordRun(
        held: JobExpectation?,
        run: RecordedRun,
        tolerance: Duration?,
    ): JobExpectation? =
        when {
            held == null -> tolerance?.let { JobExpectation(run.key, run.nextRunAt, it) }
            run.scheduledFor.isBefore(held.expectedAt) -> held
            else -> held.copy(expectedAt = run.nextRunAt)
        }

    /** The expectations with [now] strictly after their deadline, by deadline, owner service, job. */
    fun missed(
        expectations: List<JobExpectation>,
        now: Instant,
    ): List<JobExpectation> {
        val keys = HashSet<JobKey>()
        expectations.forEach { require(keys.add(it.key)) { "duplicate expectation key: ${it.key}" } }
        return expectations
            .filter { now.isAfter(it.deadline) }
            .sortedWith(compareBy({ it.deadline }, { it.key.ownerService }, { it.key.job }))
    }
}

/** Why no alert is raised. */
enum class MissedRunWithheld {
    ALREADY_ALERTED,
    NO_RECIPIENT,
}

/** The one alert to store: every recipient at once, no tier. */
data class MissedRunAlertDraft(
    val alertClass: String,
    val trigger: String,
    val entityRef: String,
    val alertRef: String,
    val recipients: List<String>,
    val tiers: List<Int>,
    val channel: AccreditationChannel,
)

sealed interface MissedRunDecision {
    data class Raise(
        val draft: MissedRunAlertDraft,
    ) : MissedRunDecision

    /** NO_RECIPIENT is not an acceptable end state: the caller must retry or dead-letter, never drop it. */
    data class Withhold(
        val reason: MissedRunWithheld,
    ) : MissedRunDecision
}

/** Decides the platform alert of a missed run. Keeps no state. */
object MissedRunAlert {
    fun alertRef(missed: JobExpectation): String =
        "missed-run:${missed.key.ownerService}:${missed.key.job}:$PLATFORM_ENTITY_REF:${missed.expectedAt}"

    fun decide(
        missed: JobExpectation,
        recipients: List<String>,
        alertedRefs: Set<String>,
    ): MissedRunDecision {
        val ref = alertRef(missed)
        val distinct = recipients.filter { it.isNotBlank() }.distinct()
        return when {
            ref in alertedRefs -> {
                MissedRunDecision.Withhold(MissedRunWithheld.ALREADY_ALERTED)
            }

            distinct.isEmpty() -> {
                MissedRunDecision.Withhold(MissedRunWithheld.NO_RECIPIENT)
            }

            else -> {
                MissedRunDecision.Raise(
                    MissedRunAlertDraft(
                        alertClass = CLASS_MISSED_RUN,
                        trigger = TRIGGER_JOB_NOT_RUN,
                        entityRef = PLATFORM_ENTITY_REF,
                        alertRef = ref,
                        recipients = distinct,
                        tiers = emptyList(),
                        channel = AccreditationChannel.EMAIL,
                    ),
                )
            }
        }
    }
}
