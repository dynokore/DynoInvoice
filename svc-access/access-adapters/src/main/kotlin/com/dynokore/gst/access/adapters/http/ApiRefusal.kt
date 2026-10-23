package com.dynokore.gst.access.adapters.http

import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import tools.jackson.databind.json.JsonMapper
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
 * A 409 for which the error catalogue holds no code (INV-Q-8845 A): the problem body carries a detail text and no
 * `code`, and the `type` stays `about:blank` (TPL-08, API-20). Never SRT-008, which means a stale aggregate version.
 */
class ApiConflict(
    val detail: String,
) : RuntimeException(detail)

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

    @ExceptionHandler(ApiConflict::class)
    fun conflict(conflict: ApiConflict): ResponseEntity<String> {
        val body =
            JsonMapper
                .builder()
                .build()
                .createObjectNode()
                .apply {
                    put("type", "about:blank")
                    put("title", "Conflict")
                    put("status", HttpStatus.CONFLICT.value())
                    put("detail", conflict.detail)
                    put(
                        "correlationId",
                        CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString(),
                    )
                }.toString()
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .header(HttpHeaders.CONTENT_TYPE, Problems.CONTENT_TYPE)
            .body(body)
    }
}
