package com.dynokore.gst.submission.adapters.http

import com.dynokore.gst.submission.application.readiness.GetLatestReadinessCheck
import com.dynokore.gst.submission.application.readiness.LatestCheckResult
import com.dynokore.gst.submission.application.readiness.StoredReadinessCheck
import com.dynokore.gst.submission.contract.api.ReadinessApi
import com.dynokore.gst.submission.contract.model.OutcomeValues
import com.dynokore.gst.submission.contract.model.ReadinessCheckRecord
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue
import java.time.ZoneOffset
import java.util.UUID

/** ARC-19, CS-14: the one place a [ComplianceRefusal] of [ReadinessController] becomes the API-20 envelope. */
@RestControllerAdvice(assignableTypes = [ReadinessController::class])
class ReadinessRefusalAdvice {
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
 * The readiness and filing operations of the submission contract (TPL-08). Every operation answers 501 until the
 * task named for it in `operations.tsv` overrides its method: the generated interface's default is NOT_IMPLEMENTED.
 *
 * `getLatestReadinessCheck` (INV-REQ-500, INV-T-21141): the acting user is the verified principal only; the entity and
 * period come from the path; the refusals are the closed API-20 envelope of [ReadinessRefusalAdvice] (SRT-001/-004/-005).
 */
@RestController
open class ReadinessController(
    private val latest: GetLatestReadinessCheck,
    private val http: ObjectProvider<HttpServletRequest>,
) : ReadinessApi {
    override fun getLatestReadinessCheck(
        entityRef: String,
        periodRef: String,
    ): ResponseEntity<ReadinessCheckRecord> {
        val caller =
            http.getObject().getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal
                ?: throw ComplianceRefusal(ClosedErrorCode.UNAUTHENTICATED)
        // fail closed: a verified principal without a tenant is not bound to any data (as ComplianceMetricsController)
        if (caller.tenant == null) throw ComplianceRefusal(ClosedErrorCode.UNAUTHENTICATED)
        return when (val result = latest.execute(caller.actor, entityRef, periodRef)) {
            is LatestCheckResult.Found -> ResponseEntity.ok(record(result.check))
            LatestCheckResult.NotFound -> throw ComplianceRefusal(ClosedErrorCode.NOT_FOUND)
            LatestCheckResult.Forbidden -> throw ComplianceRefusal(ClosedErrorCode.ACCESS_DENIED)
        }
    }

    private fun record(check: StoredReadinessCheck) =
        ReadinessCheckRecord(
            checkId = check.checkId,
            verdict = ReadinessCheckRecord.Verdict.valueOf(check.verdict.name),
            checkedAt = check.checkedAt.atOffset(ZoneOffset.UTC),
            approver = check.approver,
            outcomeValues = check.outcomeValues?.let { MAPPER.readValue<OutcomeValues>(it) },
        )

    private companion object {
        val MAPPER = jacksonObjectMapper()
    }
}
