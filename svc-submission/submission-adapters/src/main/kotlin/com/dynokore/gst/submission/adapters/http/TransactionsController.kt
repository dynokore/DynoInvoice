package com.dynokore.gst.submission.adapters.http

import com.dynokore.gst.submission.application.replay.ReplayBySelection
import com.dynokore.gst.submission.application.replay.ReplayBySelectionRequest
import com.dynokore.gst.submission.application.replay.ReplayBySelectionResult
import com.dynokore.gst.submission.application.replay.ReplayFailureClasses
import com.dynokore.gst.submission.application.replay.ReplaySingleVersion
import com.dynokore.gst.submission.application.replay.ReplaySingleVersionRequest
import com.dynokore.gst.submission.application.replay.ReplaySingleVersionResult
import com.dynokore.gst.submission.contract.api.TransactionsApi
import com.dynokore.gst.submission.contract.model.ReplayResult
import com.dynokore.gst.submission.contract.model.ReplayScopeRequest
import com.dynokore.gst.submission.contract.model.ReplayScopeResult
import com.dynokore.gst.submission.contract.model.ReplaySkip
import com.dynokore.gst.submission.contract.model.SubmissionPage
import com.dynokore.gst.submission.domain.ordering.InFlight
import com.dynokore.gst.submission.domain.replay.ReplayCandidate
import com.dynokore.gst.submission.domain.replay.ReplayScope
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.SubmissionErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.identity.MachineTokenFilter
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID
import com.dynokore.gst.submission.contract.model.ReconciliationBucket as ContractBucket

/** A refusal with a code of the submission family (SUB-008, SUB-025), rendered by [TransactionsRefusalAdvice]. */
class SubmissionRefusal(
    val code: SubmissionErrorCode,
) : RuntimeException(code.code)

/** ARC-19, CS-14: the one place a [ComplianceRefusal] of [TransactionsController] becomes the API-20 envelope. */
@RestControllerAdvice(assignableTypes = [TransactionsController::class])
class TransactionsRefusalAdvice {
    @ExceptionHandler(ComplianceRefusal::class)
    fun refuse(refusal: ComplianceRefusal): ResponseEntity<String> {
        val problem =
            Problems.closed(
                refusal.code,
                CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString(),
            )
        val headers = HttpHeaders()
        headers.set(HttpHeaders.CONTENT_TYPE, Problems.CONTENT_TYPE)
        return ResponseEntity.status(problem.status).headers(headers).body(problem.body)
    }

    @ExceptionHandler(SubmissionRefusal::class)
    fun refuse(refusal: SubmissionRefusal): ResponseEntity<String> {
        val problem =
            Problems.renderSubmission(
                refusal.code,
                CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString(),
            )
        val headers = HttpHeaders()
        problem.headers.forEach { (name, value) -> headers.set(name, value) }
        return ResponseEntity.status(problem.status).headers(headers).body(problem.body)
    }

    /** A path variable that does not parse (a malformed Doc UUID) is SRT-001, before any read. */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun refuse(mismatch: MethodArgumentTypeMismatchException): ResponseEntity<String> {
        System
            .getLogger(TransactionsRefusalAdvice::class.java.name)
            .log(System.Logger.Level.WARNING, "malformed path variable ${mismatch.name}")
        return refuse(ComplianceRefusal(ClosedErrorCode.CONTRACT_VIOLATION))
    }
}

/**
 * The submission-list, transaction and replay operations of the submission contract (TPL-08).
 * `replaySubmissionsByScope` (INV-REQ-575) is served here, and `listSubmissions` (INV-REQ-975) through the
 * [SubmissionListingHandler] delegate; the permission is decided by [ReplayBySelection]
 * through `AuthorityReplica.decide`, and this class reads no role name and holds no permission string. Every other
 * operation answers 501 until the task named for it in `operations.tsv` overrides its method.
 */
@RestController
@Suppress("TooManyFunctions") // one controller per generated interface (OpenAPI Generator pattern, ruled)
open class TransactionsController(
    private val replay: ReplayBySelection,
    private val replayOne: ReplaySingleVersion,
    private val clock: Clock,
    private val requests: ObjectProvider<HttpServletRequest>,
    private val listing: SubmissionListingHandler,
) : TransactionsApi {
    override fun replaySubmissionsByScope(
        entityRef: String,
        replayScopeRequest: ReplayScopeRequest,
    ): ResponseEntity<ReplayScopeResult> {
        val caller = caller()
        val tenant = caller.tenant ?: throw ComplianceRefusal(ClosedErrorCode.UNAUTHENTICATED)
        val limit = replayScopeRequest.limit ?: MAX_LIMIT
        val scope = validated(entityRef, replayScopeRequest, limit)
        refuseOutsideScope(caller, entityRef)
        val result =
            replay.handle(
                ReplayBySelectionRequest(tenant, Principal(caller.actor, PrincipalKind.HUMAN), scope, limit),
                CurrentDeadline.current() ?: error("no call deadline is bound"),
            )
        return when (result) {
            is ReplayBySelectionResult.Forbidden -> throw ComplianceRefusal(ClosedErrorCode.ACCESS_DENIED)
            is ReplayBySelectionResult.Done -> ResponseEntity.ok(render(entityRef, result))
        }
    }

    /** `listSubmissions` (INV-REQ-975, INV-REQ-977, INV-REQ-979): forwarded to [SubmissionListingHandler]. */
    @Suppress("LongParameterList") // the generated operation signature
    override fun listSubmissions(
        entityRef: String,
        periodRef: String,
        direction: String?,
        submissionType: String?,
        state: String?,
        categoryCode: String?,
        counterparty: String?,
        amountMin: java.math.BigDecimal?,
        amountMax: java.math.BigDecimal?,
        acknowledged: Boolean?,
        bucket: ContractBucket?,
        sort: String?,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<SubmissionPage> =
        ResponseEntity.ok(
            listing.list(
                machineCaller(),
                ListingQuery(
                    entityRef,
                    periodRef,
                    direction,
                    submissionType,
                    state,
                    categoryCode,
                    counterparty,
                    amountMin,
                    amountMax,
                    acknowledged,
                    bucket,
                    sort,
                    limit,
                    cursor,
                ),
            ),
        )

    /**
     * `replaySubmissions` (INV-REQ-575). The acting user id of the principal (never a role) is what the use case
     * receives; a version outside the caller's entity set is decided by the use case through the replica.
     */
    override fun replaySubmissions(
        docUuid: UUID,
        version: Int,
    ): ResponseEntity<ReplayResult> {
        val caller = caller()
        val tenant = verifiedTenant(caller)
        requirePositive(version)
        val result =
            replayGuarded(
                ReplaySingleVersionRequest(
                    tenant,
                    Principal(caller.actor, PrincipalKind.HUMAN),
                    docUuid.toString(),
                    version,
                ),
            )
        refusalOf(result)?.let { throw it }
        val at = OffsetDateTime.ofInstant(clock.instant(), clock.zone)
        return ResponseEntity.ok(ReplayResult(docUuid, version, ReplayResult.State.QUEUED, at))
    }

    private fun verifiedTenant(caller: EndUserPrincipal) =
        caller.tenant ?: throw ComplianceRefusal(ClosedErrorCode.UNAUTHENTICATED)

    /** A version below 1 is SRT-001 before any read. */
    private fun requirePositive(version: Int) {
        if (version < 1) throw ComplianceRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
    }

    /** An exception from the use case is SRT-010 (API-22: no detail). */
    @Suppress("TooGenericExceptionCaught") // any use-case failure is SRT-010; the cause is logged, never returned
    private fun replayGuarded(request: ReplaySingleVersionRequest): ReplaySingleVersionResult =
        try {
            replayOne.handle(request, CurrentDeadline.current() ?: error("no call deadline is bound"))
        } catch (e: Exception) {
            // NIST SP 800-92: the server-side cause is logged; the response carries no detail (API-22).
            LOG.log(System.Logger.Level.ERROR, "replaySubmissions: use case failed for version ${request.version}", e)
            throw ComplianceRefusal(ClosedErrorCode.INTERNAL_ERROR)
        }

    /** The refusal a use-case result stands for; null for Requeued and AlreadyQueued, which answer 200. */
    private fun refusalOf(result: ReplaySingleVersionResult): RuntimeException? =
        when (result) {
            ReplaySingleVersionResult.Requeued, ReplaySingleVersionResult.AlreadyQueued -> null
            ReplaySingleVersionResult.InFlightConflict -> SubmissionRefusal(SubmissionErrorCode.IN_FLIGHT_BREACH)
            ReplaySingleVersionResult.NotFound -> ComplianceRefusal(ClosedErrorCode.NOT_FOUND)
            is ReplaySingleVersionResult.NotReplayable -> SubmissionRefusal(SubmissionErrorCode.VERSION_NOT_REPLAYABLE)
            is ReplaySingleVersionResult.Forbidden -> ComplianceRefusal(ClosedErrorCode.ACCESS_DENIED)
        }

    /** API-24: an entityRef outside the token's current entity set is SRT-005 (the contract). */
    private fun refuseOutsideScope(
        caller: EndUserPrincipal,
        entityRef: String,
    ) {
        if (caller.entitySetCurrent && !caller.covers(EntityRef(entityRef))) {
            throw ComplianceRefusal(ClosedErrorCode.NOT_FOUND)
        }
    }

    /** A body or limit that breaks the contract, or a class the writer cannot store, is SRT-001 before any read. */
    private fun validated(
        entityRef: String,
        body: ReplayScopeRequest,
        limit: Int,
    ): ReplayScope {
        if (limit !in 1..MAX_LIMIT) throw ComplianceRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
        return try {
            ReplayScope(entityRef, body.periodRef, body.docUuid?.toString(), body.failureClass)
                .also(ReplayFailureClasses::validate)
        } catch (_: IllegalArgumentException) {
            throw ComplianceRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
        }
    }

    private fun render(
        entityRef: String,
        done: ReplayBySelectionResult.Done,
    ): ReplayScopeResult {
        val at = OffsetDateTime.ofInstant(clock.instant(), clock.zone)
        return ReplayScopeResult(
            entityRef = entityRef,
            matchedCount = done.replayed.size + done.skipped.size,
            truncated = done.matchedTruncated,
            replayed = done.replayed.map { it.toReplayed(at) },
            skipped = done.skipped.map { ReplaySkip(UUID.fromString(it.docUuid), it.version, InFlight.SUB_008) },
        )
    }

    private fun ReplayCandidate.toReplayed(at: OffsetDateTime) =
        ReplayResult(UUID.fromString(docUuid), version, ReplayResult.State.QUEUED, at)

    private fun caller(): EndUserPrincipal =
        requests.getObject().getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal
            ?: throw ComplianceRefusal(ClosedErrorCode.UNAUTHENTICATED)

    /** The verified machine principal of the route; absent or of another kind is 401, even with an end-user one. */
    private fun machineCaller(): Principal =
        (requests.getObject().getAttribute(MachineTokenFilter.PRINCIPAL_ATTRIBUTE) as? Principal)
            ?.takeIf { it.kind == PrincipalKind.MACHINE }
            ?: throw ComplianceRefusal(ClosedErrorCode.UNAUTHENTICATED)

    private companion object {
        const val MAX_LIMIT = 500
        val LOG: System.Logger = System.getLogger(TransactionsController::class.java.name)
    }
}
