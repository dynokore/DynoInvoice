package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.TokenIssuerUnavailableException
import com.dynokore.platform.serviceruntime.jobs.JobCommandOutcome
import com.dynokore.platform.serviceruntime.jobs.JobCommandRequest
import com.dynokore.platform.serviceruntime.jobs.JobCommands
import com.dynokore.platform.serviceruntime.jobs.JobEntityScope
import com.dynokore.platform.serviceruntime.jobs.JobPauseState
import com.dynokore.platform.serviceruntime.jobs.JobRunState
import com.dynokore.platform.serviceruntime.jobs.JobTarget
import com.dynokore.platform.serviceruntime.jobs.TransactionalJobCommandJournal
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
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
 *   caller's tenant and `sub` are bound for the command (ARC-21, INV-REQ-854 b1, INV-Q-4268); the roles are checked
 *   by the service's `JobCommandAuthorizer` against its authority replica inside [JobCommands].
 * - A token without a tenant (coordinator ruling INV-Q-4115) binds the platform tenant with its `sub` only for a
 *   platform-level target: a whole-job command or any command on a PLATFORM-scoped job. A tenant-scoped target (an
 *   `entityRef` of an ENTITY-scoped or unknown job) is 403 SRT-004 before anything is bound; no request runs
 *   unbound.
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
    private val scope: TenantScope,
    private val idempotency: IdempotencyStore,
    private val authority: AuthorityReplica? = null,
    private val clock: Clock = Clock.systemUTC(),
) : HttpServlet() {
    private val mapper = ObjectMapper()

    override fun service(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
        val answer =
            try {
                handle(request, correlation)
            } catch (expected: RuntimeException) {
                log.error("job command failed: {}", expected.javaClass.name) // CS-34: class only
                Problems.serverError(correlation)
            }
        response.status = answer.status
        response.contentType = if (answer.status >= HTTP_ERRORS) Problems.CONTENT_TYPE else JSON
        response.characterEncoding = StandardCharsets.UTF_8.name()
        response.writer.write(answer.body)
    }

    /**
     * API-24: the entity inside the caller's entity set. While the authority replica catches up the set is not
     * current, so the check asks the authority decision, and through it the access-svc fallback, for
     * [JOB_OPERATE] on that entity (coordinator rulings INV-Q-3771, INV-Q-3782); it is never skipped.
     */
    private fun inScope(
        principal: EndUserPrincipal,
        entity: EntityRef,
    ): Boolean {
        val tenant = principal.tenant
        if (principal.entitySetCurrent || tenant == null || authority == null) return principal.covers(entity)
        val request =
            AuthorizationRequest(tenant, Principal(principal.actor, PrincipalKind.HUMAN), entity.value, JOB_OPERATE)
        return authority.decide(request, CurrentDeadline.current() ?: Deadline.after(FALLBACK_BUDGET, clock)).allowed
    }

    private class Admitted(
        val action: JobAction,
        val principal: EndUserPrincipal,
        val parsed: ParsedJobCommand,
        val body: ByteArray,
    )

    private fun handle(
        request: HttpServletRequest,
        correlation: String,
    ): StoredResponse {
        val path = CommandHttp.path(request)
        return when (val step = admit(request, path, correlation)) {
            is Step.Stop -> {
                step.response
            }

            is Step.Go -> {
                val admitted = step.value
                val fingerprint = CommandHttp.fingerprint(request.method, path, admitted.body)
                val outcome =
                    idempotency.once(
                        admitted.principal.tenant,
                        admitted.principal.actor,
                        admitted.parsed.key,
                        fingerprint,
                    ) { recorder ->
                        execute(admitted.action, admitted.parsed, admitted.principal, correlation, recorder)
                    }
                CommandHttp.answer(outcome, correlation)
            }
        }
    }

    private fun admit(
        request: HttpServletRequest,
        path: String,
        correlation: String,
    ): Step<Admitted> {
        val route = ROUTE.matchEntire(path) ?: return Step.Stop(Problems.notFound(correlation))
        val action = JobAction.valueOf(route.groupValues[2].uppercase())
        return CommandHttp.caller(request, principals, correlation).andThen { principal ->
            val body = CommandHttp.body(request, MAX_BODY)
            CommandHttp
                .parsed(correlation) {
                    if (body.size > MAX_BODY) CommandViolation.raise("request body too large")
                    ParsedJobCommand(
                        job = URLDecoder.decode(route.groupValues[1], StandardCharsets.UTF_8),
                        key = CommandHttp.idempotencyKey(request),
                        body = CommandHttp.jsonObject(mapper, body),
                    ).also { it.validate(action) }
                }.andThen { parsed ->
                    scoped(principal, parsed, correlation, Admitted(action, principal, parsed, body))
                }
        }
    }

    private fun scoped(
        principal: EndUserPrincipal,
        parsed: ParsedJobCommand,
        correlation: String,
        admitted: Admitted,
    ): Step<Admitted> {
        val entity = parsed.entity
        // a tenantless caller (a platform operator) reaches only a platform-level target, before anything is bound
        // (coordinator ruling INV-Q-4115); it holds no entity set, so no per-entity scope applies
        val allowed =
            if (principal.tenant == null) platformTarget(parsed) else entity == null || inScope(principal, entity)
        return if (allowed) Step.Go(admitted) else Step.Stop(Problems.accessDenied(correlation))
    }

    private fun execute(
        action: JobAction,
        parsed: ParsedJobCommand,
        principal: EndUserPrincipal,
        correlation: String,
        recorder: IdempotencyRecorder,
    ): StoredResponse {
        // the API-15 guard, lookup and record are inside the command's own transaction (ARC-05, TPL-04)
        val journal =
            object : TransactionalJobCommandJournal {
                override fun begin() = recorder.begin()

                override fun withinTransaction(outcome: JobCommandOutcome.Done<*>) =
                    recorder.record(JobCommandResponses.render(action, outcome, correlation))
            }
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
        return CommandHttp.bound(scope, principal) {
            try {
                when (action) {
                    JobAction.RUN -> {
                        JobCommandResponses.runResponse(commands.runNow(command), correlation)
                    }

                    JobAction.PAUSE -> {
                        JobCommandResponses.pauseResponse(
                            commands.pause(command, parsed.until),
                            correlation,
                        )
                    }

                    JobAction.RESUME -> {
                        JobCommandResponses.pauseResponse(commands.resume(command), correlation)
                    }
                }
            } catch (invalid: IllegalArgumentException) {
                // JobRuntime's contract violation: an entityRef from the platform operator role for a
                // PLATFORM-scoped job, which has no per-entity share (INV-Q-3537).
                Problems.contractViolation(invalid.message.orEmpty(), correlation)
            }
        }
    }

    /**
     * A tenantless caller's command has a platform-level target (coordinator ruling INV-Q-4115): a whole-job command
     * (no `entityRef`) or any command on a PLATFORM-scoped job. An unknown job is not one.
     */
    private fun platformTarget(parsed: ParsedJobCommand): Boolean =
        parsed.entity == null ||
            commands.registered().any { it.name == parsed.job && it.entityScope == JobEntityScope.PLATFORM }

    companion object {
        /** The §22.3 permission for run now, pause and resume (FR-AG.64, INV-REQ-859, INV-Q-3782). */
        const val JOB_OPERATE = "job.operate"
        private val FALLBACK_BUDGET: Duration = Duration.ofSeconds(2)

        /** The servlet mapping of the three operations. */
        const val MAPPING = "/internal/v1/jobs/*"
        private val ROUTE = Regex("^/internal/v1/jobs/([^/]+)/(run|pause|resume)$")
        private const val JSON = "application/json"
        private const val MAX_BODY = 16 * 1024
        private const val HTTP_ERRORS = 400
        private val log = LoggerFactory.getLogger(JobCommandServlet::class.java)
    }
}
