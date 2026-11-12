package com.dynokore.gst.submission.adapters.http

import com.dynokore.gst.submission.application.replay.ReplayBySelection
import com.dynokore.gst.submission.application.replay.ReplayBySelectionRequest
import com.dynokore.gst.submission.application.replay.ReplayBySelectionResult
import com.dynokore.gst.submission.application.replay.ReplayFailureClasses
import com.dynokore.gst.submission.contract.api.TransactionsApi
import com.dynokore.gst.submission.contract.model.ReplayResult
import com.dynokore.gst.submission.contract.model.ReplayScopeRequest
import com.dynokore.gst.submission.contract.model.ReplayScopeResult
import com.dynokore.gst.submission.contract.model.ReplaySkip
import com.dynokore.gst.submission.domain.ordering.InFlight
import com.dynokore.gst.submission.domain.replay.ReplayCandidate
import com.dynokore.gst.submission.domain.replay.ReplayScope
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
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
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

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
}

/**
 * The submission-list, transaction and replay operations of the submission contract (TPL-08). `replaySubmissionsByScope`
 * (INV-REQ-575) is served here; the permission is decided by [ReplayBySelection] through `AuthorityReplica.decide`
 * and this class reads no role name and holds no permission string. Every other operation answers 501 until the task
 * named for it in `operations.tsv` overrides its method.
 */
@RestController
open class TransactionsController(
    private val replay: ReplayBySelection,
    private val clock: Clock,
    private val requests: ObjectProvider<HttpServletRequest>,
) : TransactionsApi {
    override fun replaySubmissionsByScope(
        entityRef: String,
        replayScopeRequest: ReplayScopeRequest,
    ): ResponseEntity<ReplayScopeResult> {
        val caller = caller()
        val tenant = caller.tenant ?: throw ComplianceRefusal(ClosedErrorCode.UNAUTHENTICATED)
        val limit = replayScopeRequest.limit ?: MAX_LIMIT
        val scope = validated(entityRef, replayScopeRequest, limit)
        if (caller.entitySetCurrent && !caller.covers(EntityRef(entityRef))) {
            throw ComplianceRefusal(ClosedErrorCode.NOT_FOUND)
        }
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

    private companion object {
        const val MAX_LIMIT = 500
    }
}
