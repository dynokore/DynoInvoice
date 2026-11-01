package com.dynokore.gst.rules.adapters.web

import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.UUID

/** A refused request: the closed service-runtime case and what the caller may be told (API-22: no server detail). */
class ApiRefusal(
    val code: ClosedErrorCode,
    val detail: String? = null,
) : RuntimeException("${code.code}: ${detail ?: code.title}")

/** ARC-19, CS-14: the one place a refusal becomes the API-20 envelope. */
@RestControllerAdvice
class ApiRefusalAdvice {
    /** A parameter outside the bounds the contract declares (for example `limit` above its maximum) is a 400. */
    @ExceptionHandler(ConstraintViolationException::class)
    fun invalid(
        @Suppress("UNUSED_PARAMETER") violation: ConstraintViolationException,
    ): ResponseEntity<String> =
        refuse(ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "a request parameter is outside its bounds"))

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
