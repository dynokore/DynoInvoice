package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

/**
 * A refused request: the closed service-runtime case and, optionally, what the caller may be told (never a server
 * detail, API-22).
 */
class ApiRefusal(
    val code: ClosedErrorCode,
    val detail: String? = null,
) : RuntimeException("${code.code}: ${detail ?: code.title}")

/** ARC-19, CS-14: the one place a refusal becomes the API-20 envelope. */
@RestControllerAdvice
class ApiRefusalAdvice {
    @ExceptionHandler(ApiRefusal::class)
    fun refuse(refusal: ApiRefusal): ResponseEntity<String> {
        val problem =
            Problems.render(
                refusal.code,
                CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString(),
                refusal.detail,
            )
        val headers = HttpHeaders()
        problem.headers.forEach { (name, value) -> headers.add(name, value) }
        return ResponseEntity.status(problem.status).headers(headers).body(problem.body)
    }
}

/** The refusals the tenancy operations share (SRT-001 for a malformed request, SRT-005 for an absent node). */
internal fun invalid(message: String): Nothing = throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, message)

internal fun notFound(): Nothing = throw ApiRefusal(ClosedErrorCode.NOT_FOUND)

/**
 * The contract answers 409 for a conflict with the tenant's state. The closed service-runtime set has no code for a
 * business conflict and the contract lists none (`x-error-codes: []`); STALE_AGGREGATE is the one 409 the set holds
 * until the catalogue gains a tenancy code (reported as a gap in the merge request).
 */
internal fun conflict(message: String): Nothing = throw ApiRefusal(ClosedErrorCode.STALE_AGGREGATE, message)
