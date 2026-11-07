package com.dynokore.gst.transport.adapters.http

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

/**
 * A refusal under a catalogue code that is not one of the closed service-runtime cases (API-20: `type` is the URI of
 * the code's documentation). [code] is a constant of the service, never read from a request.
 */
class CatalogueRefusal(
    val status: Int,
    val code: String,
    val title: String,
) : RuntimeException("$code: $title")

/** ARC-19, CS-14: the one place a refusal becomes the API-20 envelope. */
@RestControllerAdvice
class ApiRefusalAdvice {
    @ExceptionHandler(CatalogueRefusal::class)
    fun refuse(refusal: CatalogueRefusal): ResponseEntity<String> {
        val correlation = CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()
        val body =
            """{"type":"https://errors.invoice.internal/${refusal.code}","title":"${refusal.title}",""" +
                """"status":${refusal.status},"code":"${refusal.code}","correlationId":"$correlation"}"""
        val headers = HttpHeaders()
        headers.add(HttpHeaders.CONTENT_TYPE, "application/problem+json")
        return ResponseEntity.status(refusal.status).headers(headers).body(body)
    }

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
