package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.TenancyErrorCode
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

/** A business-conflict refusal with its tenancy catalogue code (TNC-001..003), rendered by `Problems.renderTenancy`. */
class TenancyRefusal(
    val code: TenancyErrorCode,
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

    @ExceptionHandler(TenancyRefusal::class)
    fun refuse(refusal: TenancyRefusal): ResponseEntity<String> {
        val problem =
            Problems.renderTenancy(
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

/** The contract answers 409 for a conflict with the tenant's state; the code is a TNC catalogue code (INV-Q-7553 A). */
internal fun conflict(
    code: TenancyErrorCode,
    message: String,
): Nothing = throw TenancyRefusal(code, message)
