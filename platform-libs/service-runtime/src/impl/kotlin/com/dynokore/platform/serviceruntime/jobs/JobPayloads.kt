package com.dynokore.platform.serviceruntime.jobs

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.connection.ConnectionFailure
import com.dynokore.platform.serviceruntime.connection.ConnectionState
import tools.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * The payloads of the platform events this package writes, per platform-contracts service-runtime
 * schemas: `job-registered-payload`, `job-run-recorded-payload` (with the optional INV-T-3007 fields) and
 * `connection-status-changed-payload`. Types per API-40.
 */
object JobPayloads {
    const val JOB_REGISTERED = "com.dynokore.gst.platform.JobRegistered.v1"
    const val JOB_RUN_RECORDED = "com.dynokore.gst.platform.JobRunRecorded.v1"
    const val CONNECTION_STATUS_CHANGED = "com.dynokore.gst.platform.ConnectionStatusChanged.v1"

    private val mapper = ObjectMapper()

    /**
     * The prior state a run-now audits (INV-Q-3533, INV-Q-3563): every pause in force on its scope, keyed by
     * entity (absent = job-wide), with its until and pausedAt, and whether the run overrode it or skipped that
     * entity because its caller cannot lift it.
     */
    fun runNowPriorState(pauses: List<PauseInForce>): String {
        val node = mapper.createObjectNode()
        node.put("state", "PAUSED")
        val array = node.putArray("pausesInForce")
        pauses.forEach { pause ->
            val item = array.addObject()
            pause.entity?.let { item.put("entityRef", it.value) }
            item.put("until", pause.until.toString())
            item.put("pausedAt", pause.pausedAt.toString())
            item.put("handling", if (pause.skipped) "SKIPPED" else "OVERRIDDEN")
        }
        return mapper.writeValueAsString(node)
    }

    fun jobRegistered(job: JobDefinition): String {
        val node = mapper.createObjectNode()
        node.put("job", job.name)
        node.put("ownerService", job.ownerService)
        node.put("entityScope", job.entityScope.name)
        node.put("schedule", job.schedule.cron)
        node.put("criticality", job.criticality.name)
        node.put("tolerance", job.tolerance.toString())
        return mapper.writeValueAsString(node)
    }

    @Suppress("LongParameterList") // a public factory with defaulted parameters; INV-Q-4629 (CS-50)
    fun jobRunRecorded(
        job: JobDefinition,
        target: JobTarget,
        failureReason: String?,
        durationMillis: Long,
        scheduledFor: Instant,
        nextRunAt: Instant,
    ): String {
        val node = mapper.createObjectNode()
        node.put("job", job.name)
        node.put("ownerService", job.ownerService)
        if (target is JobTarget.Entity) node.put("entityScope", target.entity.value)
        val outcome = node.putObject("outcome")
        outcome.put("status", if (failureReason == null) JobRunStatus.SUCCEEDED.name else JobRunStatus.FAILED.name)
        failureReason?.let { outcome.put("reason", it) }
        node.put("durationMillis", durationMillis)
        node.put("schedule", job.schedule.cron)
        node.put("criticality", job.criticality.name)
        node.put("tolerance", job.tolerance.toString())
        node.put("scheduledFor", scheduledFor.toString())
        node.put("nextRunAt", nextRunAt.toString())
        return mapper.writeValueAsString(node)
    }

    fun connectionStatusChanged(
        connection: String,
        state: ConnectionState,
        lastFailure: ConnectionFailure?,
    ): String {
        val node = mapper.createObjectNode()
        node.put("connection", connection)
        node.put("state", state.wire)
        lastFailure?.let {
            val failure = node.putObject("lastFailure")
            failure.put("at", it.at.toString())
            failure.put("reason", it.reason)
        }
        return mapper.writeValueAsString(node)
    }
}

/** A pause in force when a run-now was accepted: [entity] is null for the job-wide pause. */
data class PauseInForce(
    val entity: EntityRef?,
    val until: Instant,
    val pausedAt: Instant,
    val skipped: Boolean,
)
