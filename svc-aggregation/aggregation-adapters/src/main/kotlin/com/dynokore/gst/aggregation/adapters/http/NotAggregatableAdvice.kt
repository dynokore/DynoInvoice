package com.dynokore.gst.aggregation.adapters.http

import com.dynokore.gst.aggregation.domain.AggregateIntakeCheck
import com.dynokore.gst.aggregation.domain.IntakeVerdict
import com.dynokore.gst.aggregation.domain.NotAggregatable
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import tools.jackson.databind.DatabindException
import tools.jackson.databind.exc.InvalidFormatException
import tools.jackson.databind.node.JsonNodeFactory
import java.util.UUID

/**
 * INV-REQ-410, ING-006: an `aggregateType` other than POS, STI or PCP is refused with the specific code, never the
 * generic validation failure, and with no override. The generated request type closes the enum, so the value is
 * refused while the body is read; this advice turns exactly that failure (the property `aggregateType`) into the
 * API-20 envelope of ING-006. Every other unreadable body keeps the framework answer.
 */
@RestControllerAdvice
class NotAggregatableAdvice {
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(failure: HttpMessageNotReadableException): ResponseEntity<*> {
        val offered = offeredAggregateType(failure)
        val refused = offered?.let { AggregateIntakeCheck.check(it.value) as? IntakeVerdict.Refused }
        return if (refused == null) {
            ResponseEntity.status(HttpStatus.BAD_REQUEST).build<Unit>()
        } else {
            refusal(refused.refusal)
        }
    }

    private class Offered(
        val value: String?,
    )

    private fun offeredAggregateType(failure: Throwable): Offered? =
        generateSequence(failure) { it.cause }
            .filterIsInstance<DatabindException>()
            .firstOrNull { error -> error.path.lastOrNull()?.propertyName == AGGREGATE_TYPE }
            ?.let { Offered((it as? InvalidFormatException)?.value?.toString()) }

    private fun refusal(refusal: NotAggregatable): ResponseEntity<String> {
        val body =
            JsonNodeFactory.instance.objectNode().apply {
                put("type", "https://errors.invoice.internal/${refusal.code}")
                put("title", TITLE)
                put("status", HttpStatus.UNPROCESSABLE_CONTENT.value())
                put("correlationId", CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString())
                put("code", refusal.code)
                put("severity", "BLOCK")
                put("disposition", if (refusal.terminal) "TERMINAL" else "MANUAL")
                put("remediationOwner", "Integration")
            }
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).headers(headers).body(body.toString())
    }

    private companion object {
        const val AGGREGATE_TYPE = "aggregateType"
        const val TITLE = "aggregateType is not POS, STI or PCP"
    }
}
