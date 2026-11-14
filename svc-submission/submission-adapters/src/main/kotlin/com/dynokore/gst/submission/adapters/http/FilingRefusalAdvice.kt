package com.dynokore.gst.submission.adapters.http

import com.dynokore.gst.submission.application.filing.AmbiguousDueDateException
import com.dynokore.platform.serviceruntime.http.PeriodErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

/** A `confirmFiling` refusal of the period family (PER-001, INV-T-20923). */
class FilingPeriodRefusal(
    val code: PeriodErrorCode,
) : RuntimeException(code.code)

/** ARC-19, CS-14: the one place a [FilingPeriodRefusal] becomes the API-20 envelope (RFC 9457). */
@RestControllerAdvice(assignableTypes = [ReadinessController::class])
class FilingRefusalAdvice {
    @ExceptionHandler(FilingPeriodRefusal::class)
    fun refuse(refusal: FilingPeriodRefusal): ResponseEntity<String> {
        val problem =
            Problems.renderPeriod(
                refusal.code,
                CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString(),
            )
        val headers = HttpHeaders()
        headers.set(HttpHeaders.CONTENT_TYPE, Problems.CONTENT_TYPE)
        return ResponseEntity.status(problem.status).headers(headers).body(problem.body)
    }

    /** Ruling (g): more than one due date is a server fault, SRT-010 500 with no detail (API-22, OWASP ASVS V7). */
    @ExceptionHandler(AmbiguousDueDateException::class)
    fun ambiguous(
        @Suppress("UNUSED_PARAMETER") failure: AmbiguousDueDateException,
    ): ResponseEntity<String> {
        val problem =
            Problems.serverError(CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString())
        val headers = HttpHeaders()
        headers.set(HttpHeaders.CONTENT_TYPE, Problems.CONTENT_TYPE)
        return ResponseEntity.status(problem.status).headers(headers).body(problem.body)
    }
}
