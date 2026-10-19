package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.jobs.JobCommandOutcome
import com.dynokore.platform.serviceruntime.jobs.JobCommandRequest
import com.dynokore.platform.serviceruntime.jobs.JobPauseState
import com.dynokore.platform.serviceruntime.jobs.JobRunState
import com.dynokore.platform.serviceruntime.jobs.JobTarget
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

internal enum class JobAction { RUN, PAUSE, RESUME }

/** The validated parts of a job command request. */
internal class ParsedJobCommand(
    val job: String,
    val key: UUID,
    val body: JsonNode,
) {
    val entity: EntityRef? get() = optionalText("entityRef")?.let(::EntityRef)
    val reason: String? get() = optionalText("reason")
    val until: Instant
        get() =
            try {
                Instant.parse(optionalText("until") ?: CommandViolation.raise("until is required"))
            } catch (_: DateTimeParseException) {
                CommandViolation.raise("until must be a date-time")
            }

    fun validate(action: JobAction) {
        if (job.isBlank()) CommandViolation.raise("job is required")
        optionalText("entityRef")?.let {
            if (it.isBlank() ||
                it.length > JobTarget.MAX_ENTITY_REF
            ) {
                CommandViolation.raise("entityRef is 1 to ${JobTarget.MAX_ENTITY_REF} characters")
            }
        }
        optionalText("reason")?.let {
            if (it.isBlank() ||
                it.length > JobCommandRequest.MAX_REASON
            ) {
                CommandViolation.raise("reason is 1 to ${JobCommandRequest.MAX_REASON} characters")
            }
        }
        if (action == JobAction.PAUSE) until
    }

    private fun optionalText(field: String): String? {
        val value = body.get(field)
        return when {
            value == null || value.isNull -> null
            !value.isString -> CommandViolation.raise("$field must be a string")
            else -> value.asString()
        }
    }
}

/** The stored and sent answers of the job commands. */
internal object JobCommandResponses {
    private const val HTTP_OK = 200
    private const val HTTP_ACCEPTED = 202
    private val mapper = ObjectMapper()

    @Suppress("UNCHECKED_CAST")
    fun render(
        action: JobAction,
        done: JobCommandOutcome.Done<*>,
        correlation: String,
    ): StoredResponse =
        when (action) {
            JobAction.RUN -> {
                runResponse(done as JobCommandOutcome.Done<JobRunState>, correlation)
            }

            JobAction.PAUSE, JobAction.RESUME -> {
                pauseResponse(
                    done as JobCommandOutcome.Done<JobPauseState>,
                    correlation,
                )
            }
        }

    fun runResponse(
        outcome: JobCommandOutcome<JobRunState>,
        correlation: String,
    ): StoredResponse =
        when (outcome) {
            is JobCommandOutcome.Refused -> {
                Problems.job(outcome.code, correlation)
            }

            is JobCommandOutcome.Done -> {
                val state = outcome.state
                val node = mapper.createObjectNode()
                node.put("job", state.job)
                (state.target as? JobTarget.Entity)?.let { node.put("entityRef", it.entity.value) }
                node.put("trigger", state.trigger.name)
                node.put("scheduledFor", state.scheduledFor.toString())
                node.put("attempt", state.attempt)
                node.put("status", state.status.name)
                node.put("startedAt", state.startedAt.toString())
                node.put("repeat", state.repeat)
                StoredResponse(if (outcome.created) HTTP_ACCEPTED else HTTP_OK, mapper.writeValueAsString(node))
            }
        }

    fun pauseResponse(
        outcome: JobCommandOutcome<JobPauseState>,
        correlation: String,
    ): StoredResponse =
        when (outcome) {
            is JobCommandOutcome.Refused -> {
                Problems.job(outcome.code, correlation)
            }

            is JobCommandOutcome.Done -> {
                val state = outcome.state
                val node = mapper.createObjectNode()
                node.put("job", state.job)
                (state.target as? JobTarget.Entity)?.let { node.put("entityRef", it.entity.value) }
                node.put("state", if (state.paused) "PAUSED" else "ACTIVE")
                state.until?.let { node.put("until", it.toString()) }
                state.pausedAt?.let { node.put("pausedAt", it.toString()) }
                state.previousUntil?.let { node.put("previousUntil", it.toString()) }
                StoredResponse(HTTP_OK, mapper.writeValueAsString(node))
            }
        }
}
