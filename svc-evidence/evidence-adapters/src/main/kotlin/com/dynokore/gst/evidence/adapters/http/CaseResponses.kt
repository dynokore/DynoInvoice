package com.dynokore.gst.evidence.adapters.http

import com.dynokore.gst.evidence.application.cases.CaseAccessDenied
import com.dynokore.gst.evidence.contract.model.SecurityCase
import com.dynokore.gst.evidence.domain.cases.CaseRecord
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import jakarta.validation.ConstraintViolationException
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.time.ZoneOffset
import java.util.UUID

/** A refused case call: one closed service-runtime case, rendered once as the API-20 envelope. */
class CaseRefusal(
    val code: ClosedErrorCode,
    val detail: String? = null,
) : RuntimeException(code.code)

/** The wire shapes of the case operations (CS-14: the mapping lives in one place). */
object CaseResponses {
    /** `SecurityCase` of the contract; `recordedAt` is the stored `opened_at`. */
    fun securityCase(record: CaseRecord): SecurityCase {
        val facts = checkNotNull(record.security) { "case ${record.caseId} is not a security case" }
        return SecurityCase(
            caseId = record.caseId,
            reviewReference = facts.reviewReference,
            auditLogEntryIds = facts.auditLogEntryIds,
            outcome = facts.outcome,
            breachAssessmentOpened = facts.breachAssessmentOpened,
            recordedAt = record.openedAt.atOffset(ZoneOffset.UTC),
        )
    }

    /**
     * `PrivacyCase` of the contract as a map that holds only the members the case has: the four required ones, and
     * the privacy members only for a case opened as a privacy request. A security case has `subjectRef` equal to the
     * empty string (INV-Q-11682 A); no `subjectAccessExport` is ever present here.
     */
    fun privacyCase(record: CaseRecord): Map<String, Any> {
        val body = LinkedHashMap<String, Any>()
        body["caseId"] = record.caseId.toString()
        body["subjectRef"] = record.subjectRef.orEmpty()
        body["status"] = record.status.name
        body["openedAt"] = record.openedAt.toString()
        val privacy = record.privacy
        if (privacy != null) {
            body["requestKind"] = privacy.requestKind.name
            body["requestReference"] = privacy.requestReference
            body["receivedOn"] = privacy.receivedOn.toString()
            body["responseDeadline"] = privacy.responseDeadline.toString()
            body["deadlinePeriodDays"] = privacy.deadlinePeriodDays
            privacy.closure?.let {
                body["closedAt"] = it.closedAt.toString()
                body["outcome"] = it.outcome.name
                body["basis"] = it.basis
            }
        }
        return body
    }
}

/**
 * ARC-19, CS-14: the one place a refusal of the case operations becomes the API-20 envelope: 403 SRT-004 for a
 * permission not held, 404 SRT-005, 409 SRT-007, 422 SRT-009, 400 SRT-002 for a missing or malformed `Idempotency-Key`
 * and 400 SRT-001 for a request the contract refuses. The detail is fixed text and never a value the caller sent.
 */
@RestControllerAdvice(assignableTypes = [PrivacyController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class CaseRefusalAdvice {
    @ExceptionHandler(CaseRefusal::class)
    fun refuse(refusal: CaseRefusal): ResponseEntity<String> = answer(refusal.code, refusal.detail)

    @ExceptionHandler(CaseAccessDenied::class)
    fun denied(): ResponseEntity<String> = answer(ClosedErrorCode.ACCESS_DENIED, null)

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun missingHeader(e: MissingRequestHeaderException): ResponseEntity<String> =
        if (e.headerName == IDEMPOTENCY_KEY) {
            answer(ClosedErrorCode.IDEMPOTENCY_KEY_INVALID, "Idempotency-Key is required")
        } else {
            answer(ClosedErrorCode.CONTRACT_VIOLATION, "the request header '${e.headerName}' is missing")
        }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun wrongType(e: MethodArgumentTypeMismatchException): ResponseEntity<String> =
        if (e.name == IDEMPOTENCY_KEY_PARAMETER || e.name == IDEMPOTENCY_KEY) {
            answer(ClosedErrorCode.IDEMPOTENCY_KEY_INVALID, "Idempotency-Key must be a UUID")
        } else {
            answer(ClosedErrorCode.CONTRACT_VIOLATION, "the request parameter '${e.name}' has the wrong type")
        }

    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        HandlerMethodValidationException::class,
        ConstraintViolationException::class,
    )
    fun invalid(): ResponseEntity<String> =
        answer(ClosedErrorCode.CONTRACT_VIOLATION, "the request body does not match the contract")

    private fun answer(
        code: ClosedErrorCode,
        detail: String?,
    ): ResponseEntity<String> {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val problem = Problems.closed(code, correlation, detail)
        return ResponseEntity
            .status(problem.status)
            .header(HttpHeaders.CONTENT_TYPE, Problems.CONTENT_TYPE)
            .body(problem.body)
    }

    private companion object {
        const val IDEMPOTENCY_KEY = "Idempotency-Key"
        const val IDEMPOTENCY_KEY_PARAMETER = "idempotencyKey"
    }
}
