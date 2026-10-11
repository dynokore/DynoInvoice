package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.jobs.JobCommandJournal
import com.dynokore.platform.serviceruntime.jobs.JobCommandOutcome
import com.dynokore.platform.serviceruntime.jobs.JobCommandRequest
import com.dynokore.platform.serviceruntime.jobs.JobCommands
import com.dynokore.platform.serviceruntime.jobs.JobPauseState
import com.dynokore.platform.serviceruntime.jobs.JobRunState
import com.dynokore.platform.serviceruntime.jobs.JobTarget
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.tenancykernel.TenantContext
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * runJobNow, pauseJob and resumeJob over HTTP (INV-API-3025, INV-API-3023; platform-contracts
 * `service-runtime/openapi.yaml`), onto the service's [JobCommands] (INV-Q-3535):
 * `POST /internal/v1/jobs/{job}/run|pause|resume`.
 *
 * - The actor (`sub`) and tenant (`tenant_id`) come from the propagated end-user token, and the entity set from
 *   the authority replica, through [principals] (CS-23, API-24; owner ruling INV-Q-3716 A), never from a field;
 *   an `entityRef` outside the principal's entity set is 403 before anything runs. The
 *   caller's tenant is bound for the command (ARC-21); the roles are checked by the service's
 *   `JobCommandAuthorizer` against its authority replica inside [JobCommands].
 * - `Idempotency-Key` (a UUID, the contract's `format: uuid`) is required and kept in [idempotency]
 *   with a fingerprint of the method, path and body (API-15).
 * - A refused command is its JOB catalogue code at the code's status (API-21); each closed case (ARC-19) is
 *   its SRT code (INV-Q-3720), so no response is code-less, see [Problems].
 * - runJobNow answers 202 when it started a run, 200 for a repeat in the same run window (INV-Q-3034); pauseJob
 *   and resumeJob answer 200 with the key's own `JobPauseState`.
 */
class JobCommandServlet(
    private val commands: JobCommands,
    private val principals: EndUserPrincipalResolver,
    private val tenants: TenantContext,
    private val idempotency: IdempotencyStore,
) : HttpServlet() {
    private val mapper = ObjectMapper()

    private enum class Action { RUN, PAUSE, RESUME }

    private class Violation(
        message: String,
        val code: ClosedErrorCode = ClosedErrorCode.CONTRACT_VIOLATION,
    ) : RuntimeException(message)

    override fun service(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
        val answer =
            try {
                handle(request, correlation)
            } catch (failure: RuntimeException) {
                log.error("job command failed: {}", failure.javaClass.name) // CS-34: class only
                Problems.serverError(correlation)
            }
        response.status = answer.status
        response.contentType = if (answer.status >= HTTP_ERRORS) Problems.CONTENT_TYPE else JSON
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write(answer.body)
    }

    private fun handle(
        request: HttpServletRequest,
        correlation: String,
    ): StoredResponse {
        val path = request.requestURI.removePrefix(request.contextPath.orEmpty())
        val route = ROUTE.matchEntire(path) ?: return Problems.notFound(correlation)
        val action = Action.valueOf(route.groupValues[2].uppercase())
        if (request.method != "POST") return Problems.methodNotAllowed(correlation)
        val principal = principals.resolve(request.getHeader(AUTHORIZATION)) ?: return Problems.unauthenticated(correlation)
        val body = request.inputStream.readNBytes(MAX_BODY + 1)
        val parsed =
            try {
                if (body.size > MAX_BODY) throw Violation("request body too large")
                Parsed(
                    job = URLDecoder.decode(route.groupValues[1], StandardCharsets.UTF_8),
                    key = idempotencyKey(request.getHeader(IDEMPOTENCY_KEY)),
                    body = jsonObject(body),
                ).also { it.validate(action) }
            } catch (violation: Violation) {
                return Problems.closed(violation.code, correlation, violation.message.orEmpty())
            }
        val entity = parsed.entity
        if (entity != null && !principal.covers(entity)) return Problems.accessDenied(correlation)
        val fingerprint = fingerprint(request.method, path, body)
        val outcome =
            idempotency.once(principal.tenant, principal.actor, parsed.key, fingerprint) { recorder ->
                execute(action, parsed, principal, correlation, recorder)
            }
        return when (outcome) {
            is IdempotencyOutcome.Fresh -> outcome.response
            is IdempotencyOutcome.Replay -> outcome.response
            IdempotencyOutcome.InFlight -> Problems.inFlight(correlation)
            IdempotencyOutcome.DifferentRequest -> Problems.differentRequest(correlation)
        }
    }

    private inner class Parsed(
        val job: String,
        val key: UUID,
        val body: JsonNode,
    ) {
        val entity: EntityRef? get() = optionalText("entityRef")?.let(::EntityRef)
        val reason: String? get() = optionalText("reason")
        val until: Instant
            get() =
                try {
                    Instant.parse(optionalText("until") ?: throw Violation("until is required"))
                } catch (_: DateTimeParseException) {
                    throw Violation("until must be a date-time")
                }

        fun validate(action: Action) {
            if (job.isBlank()) throw Violation("job is required")
            optionalText("entityRef")?.let { if (it.isBlank() || it.length > JobTarget.MAX_ENTITY_REF) throw Violation("entityRef is 1 to ${JobTarget.MAX_ENTITY_REF} characters") }
            optionalText("reason")?.let { if (it.isBlank() || it.length > JobCommandRequest.MAX_REASON) throw Violation("reason is 1 to ${JobCommandRequest.MAX_REASON} characters") }
            if (action == Action.PAUSE) until
        }

        private fun optionalText(field: String): String? {
            val value = body.get(field) ?: return null
            if (value.isNull) return null
            if (!value.isString) throw Violation("$field must be a string")
            return value.asString()
        }
    }

    private fun execute(
        action: Action,
        parsed: Parsed,
        principal: EndUserPrincipal,
        correlation: String,
        recorder: IdempotencyRecorder,
    ): StoredResponse {
        // the API-15 record is written inside the command's own transaction (ARC-05, TPL-04)
        val journal = JobCommandJournal { done -> recorder.record(render(action, done, correlation)) }
        val command =
            try {
                JobCommandRequest(
                    actor = principal.actor,
                    job = parsed.job,
                    entity = parsed.entity,
                    idempotencyKey = parsed.key,
                    reason = parsed.reason,
                    correlation = CorrelationId(correlation),
                    causation = CausationId("idempotency-key:${parsed.key}"),
                    journal = journal,
                )
            } catch (invalid: IllegalArgumentException) {
                return Problems.contractViolation(invalid.message.orEmpty(), correlation)
            }
        return bound(principal) {
            try {
                when (action) {
                    Action.RUN -> runResponse(commands.runNow(command), correlation)
                    Action.PAUSE -> pauseResponse(commands.pause(command, parsed.until), correlation)
                    Action.RESUME -> pauseResponse(commands.resume(command), correlation)
                }
            } catch (invalid: IllegalArgumentException) {
                // JobRuntime's contract violation: an entityRef from the platform operator role for a
                // PLATFORM-scoped job, which has no per-entity share (INV-Q-3537).
                Problems.contractViolation(invalid.message.orEmpty(), correlation)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun render(
        action: Action,
        done: JobCommandOutcome.Done<*>,
        correlation: String,
    ): StoredResponse = when (action) {
        Action.RUN -> runResponse(done as JobCommandOutcome.Done<JobRunState>, correlation)
        Action.PAUSE, Action.RESUME -> pauseResponse(done as JobCommandOutcome.Done<JobPauseState>, correlation)
    }

    /** Binds the caller's tenant for the command (ARC-21); a caller without a tenant binds none. */
    private fun <T> bound(
        principal: EndUserPrincipal,
        block: () -> T,
    ): T = principal.tenant?.let { tenants.bind(it, block) } ?: block()

    private fun runResponse(
        outcome: JobCommandOutcome<JobRunState>,
        correlation: String,
    ): StoredResponse = when (outcome) {
        is JobCommandOutcome.Refused -> Problems.job(outcome.code, correlation)

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

    private fun pauseResponse(
        outcome: JobCommandOutcome<JobPauseState>,
        correlation: String,
    ): StoredResponse = when (outcome) {
        is JobCommandOutcome.Refused -> Problems.job(outcome.code, correlation)

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

    private fun jsonObject(body: ByteArray): JsonNode {
        if (body.isEmpty()) throw Violation("a request body is required")
        val node =
            try {
                mapper.readTree(body)
            } catch (_: tools.jackson.core.JacksonException) {
                throw Violation("request body is not JSON")
            }
        if (node == null || !node.isObject) throw Violation("request body must be a JSON object")
        return node
    }

    private fun idempotencyKey(header: String?): UUID {
        val text = header?.trim() ?: throw Violation("Idempotency-Key is required", KEY_INVALID)
        return try {
            UUID.fromString(text).also { if (it.toString() != text.lowercase()) throw Violation("Idempotency-Key must be a UUID", KEY_INVALID) }
        } catch (_: IllegalArgumentException) {
            throw Violation("Idempotency-Key must be a UUID", KEY_INVALID)
        }
    }

    /** API-15: the fingerprint of the method, path and body. */
    private fun fingerprint(
        method: String,
        path: String,
        body: ByteArray,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$method $path\n".toByteArray(StandardCharsets.UTF_8))
        digest.update(body)
        return digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
    }

    companion object {
        /** The servlet mapping of the three operations. */
        const val MAPPING = "/internal/v1/jobs/*"
        private val ROUTE = Regex("^/internal/v1/jobs/([^/]+)/(run|pause|resume)$")
        private val KEY_INVALID = ClosedErrorCode.IDEMPOTENCY_KEY_INVALID
        private const val AUTHORIZATION = "Authorization"
        private const val IDEMPOTENCY_KEY = "Idempotency-Key"
        private const val JSON = "application/json"
        private const val MAX_BODY = 16 * 1024
        private const val HTTP_OK = 200
        private const val HTTP_ACCEPTED = 202
        private const val HTTP_ERRORS = 400
        private val log = LoggerFactory.getLogger(JobCommandServlet::class.java)
    }
}
