package com.dynokore.gst.ingestion.adapters.http

import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import jakarta.validation.ConstraintViolationException
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
    fun refuse(refusal: ApiRefusal): ResponseEntity<String> = render(refusal.code, refusal.detail)

    /**
     * A contract constraint (for example a maximum length) broken before the controller runs: 400 SRT-001 with a fixed
     * detail; the constraint message, property path and sent value are never echoed (API-22, OWASP ASVS V5, V7).
     */
    @ExceptionHandler(ConstraintViolationException::class)
    fun refuseConstraintViolation(
        @Suppress("UNUSED_PARAMETER") ex: Exception,
    ): ResponseEntity<String> = render(ClosedErrorCode.CONTRACT_VIOLATION, "The request does not match the contract.")

    private fun render(
        code: ClosedErrorCode,
        detail: String?,
    ): ResponseEntity<String> {
        val problem =
            Problems.render(
                code,
                CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString(),
                detail,
            )
        val headers = HttpHeaders()
        problem.headers.forEach { (name, value) -> headers.add(name, value) }
        return ResponseEntity.status(problem.status).headers(headers).body(problem.body)
    }
}
