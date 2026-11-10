package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import java.util.UUID

/**
 * Answers a request that fails parameter or body binding with 400 SRT-001 in the API-20 envelope, once for every
 * service on service-runtime (INV-REQ-827 b3: SRT-001 is mapped once in ProblemMapper, ARC-19; INV-AC-19580). Exactly
 * three binding exceptions are handled and no other. The detail is fixed text: it names the declared parameter and
 * never holds a value the caller sent, a class name or a framework message (API-22, OWASP ASVS V7).
 *
 * Lowest precedence: a service's own advice for the same exception wins.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class BindingFailureAdvice {
    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingParameter(e: MissingServletRequestParameterException): ResponseEntity<String> =
        refuse("the request parameter '${e.parameterName}' is missing")

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun wrongType(e: MethodArgumentTypeMismatchException): ResponseEntity<String> =
        refuse("the request parameter '${e.name}' has the wrong type")

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableBody(
        @Suppress("UNUSED_PARAMETER") e: HttpMessageNotReadableException,
    ): ResponseEntity<String> = refuse(BODY_DETAIL)

    private fun refuse(detail: String): ResponseEntity<String> {
        val response =
            Problems.contractViolation(
                detail,
                CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString(),
            )
        return ResponseEntity
            .status(response.status)
            .header(HttpHeaders.CONTENT_TYPE, Problems.CONTENT_TYPE)
            .body(response.body)
    }

    private companion object {
        const val BODY_DETAIL = "the request body does not match the contract"
    }
}
