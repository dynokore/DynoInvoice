package com.dynokore.gst.controlPlane.adapters.http

import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.ProblemResponse
import com.dynokore.platform.serviceruntime.http.Problems
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import java.util.UUID

/** An API-20 error a controller raises instead of returning a body of the success type (ARC-19, CS-12). */
class ProblemException(
    val problem: ProblemResponse,
) : RuntimeException(null, null, false, false)

internal fun problem(
    code: ClosedErrorCode,
    correlationId: String,
    detail: String? = null,
): Nothing = throw ProblemException(Problems.render(code, correlationId, detail))

/** One place, one envelope (API-20, CS-14): every error answers `application/problem+json` with its catalogue code. */
@RestControllerAdvice
class ProblemAdvice {
    private val log = LoggerFactory.getLogger(ProblemAdvice::class.java)

    @ExceptionHandler(ProblemException::class)
    fun problem(e: ProblemException): ResponseEntity<String> = respond(e.problem)

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun missingHeader(e: MissingRequestHeaderException): ResponseEntity<String> =
        respond(
            Problems.render(
                if (e.headerName.equals("Idempotency-Key", ignoreCase = true)) {
                    ClosedErrorCode.IDEMPOTENCY_KEY_INVALID
                } else {
                    ClosedErrorCode.CONTRACT_VIOLATION
                },
                newCorrelation(),
            ),
        )

    @ExceptionHandler(MethodArgumentNotValidException::class, HandlerMethodValidationException::class)
    fun invalid(): ResponseEntity<String> =
        respond(Problems.render(ClosedErrorCode.CONTRACT_VIOLATION, newCorrelation()))

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<String> {
        log.error("unhandled", e) // one log line (CS-35); the response carries no detail (API-22)
        return respond(Problems.render(ClosedErrorCode.INTERNAL_ERROR, newCorrelation()))
    }

    private fun respond(problem: ProblemResponse): ResponseEntity<String> {
        val headers = HttpHeaders()
        problem.headers.forEach { (name, value) -> headers.set(name, value) }
        return ResponseEntity.status(problem.status).headers(headers).body(problem.body)
    }

    private fun newCorrelation() = UUID.randomUUID().toString()
}
