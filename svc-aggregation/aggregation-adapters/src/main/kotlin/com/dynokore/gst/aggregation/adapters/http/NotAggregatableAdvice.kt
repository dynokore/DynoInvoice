package com.dynokore.gst.aggregation.adapters.http

import com.dynokore.gst.aggregation.domain.AggregateIntakeCheck
import com.dynokore.gst.aggregation.domain.IntakeVerdict
import com.dynokore.platform.serviceruntime.http.IngestionErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.util.ContentCachingRequestWrapper
import org.springframework.web.util.WebUtils
import tools.jackson.databind.DatabindException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * INV-REQ-410, ING-006: an `aggregateType` string other than POS, STI or PCP is refused with the specific code, never
 * the generic validation failure, and with no override. The generated request type closes the enum, so the value is
 * refused while the body is read; this advice turns exactly that failure (the property `aggregateType` offered as a
 * JSON string) into the 422 of [Problems.renderIngestion] (INV-Q-8795 A, INV-Q-8797 A); no envelope is built here. An
 * absent `aggregateType`, and a present one whose JSON token is not a string (`null`, number, boolean, object, array;
 * the token type is read from the body, never the coerced text), is the contract violation SRT-001 (400,
 * [Problems.contractViolation]), not ING-006 (INV-Q-9338 A). Every other unreadable body keeps the framework answer:
 * its wire form is not ruled.
 */
@RestControllerAdvice
class NotAggregatableAdvice {
    private val mapper = JsonMapper()

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(
        failure: HttpMessageNotReadableException,
        request: HttpServletRequest,
    ): ResponseEntity<*> {
        val member = if (failsOnAggregateType(failure)) aggregateTypeMember(request) else null
        return when {
            member == null -> ResponseEntity.status(HttpStatus.BAD_REQUEST).build<Unit>()
            member.isMissingNode -> respond(Problems.contractViolation("aggregateType is required", correlationId()))
            !member.isString -> respond(Problems.contractViolation("aggregateType must be a string", correlationId()))
            AggregateIntakeCheck.check(member.asString()) is IntakeVerdict.Refused -> refused()
            else -> ResponseEntity.status(HttpStatus.BAD_REQUEST).build<Unit>()
        }
    }

    private fun refused(): ResponseEntity<*> {
        val rendered = Problems.renderIngestion(IngestionErrorCode.AGGREGATE_TYPE_NOT_PERMITTED, correlationId())
        return ResponseEntity
            .status(rendered.status)
            .headers { headers -> rendered.headers.forEach { (name, value) -> headers.set(name, value) } }
            .body(rendered.body)
    }

    private fun respond(stored: StoredResponse): ResponseEntity<String> =
        ResponseEntity
            .status(stored.status)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE)
            .body(stored.body)

    private fun correlationId(): String =
        CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString()

    private fun failsOnAggregateType(failure: Throwable): Boolean =
        generateSequence(failure) { it.cause }
            .filterIsInstance<DatabindException>()
            .any { error -> error.path.lastOrNull()?.propertyName == AGGREGATE_TYPE }

    /** The `aggregateType` member of the body the converter read; null when the body is not a readable JSON object. */
    private fun aggregateTypeMember(request: HttpServletRequest): JsonNode? {
        val cached = WebUtils.getNativeRequest(request, ContentCachingRequestWrapper::class.java) ?: return null
        val tree = runCatching { mapper.readTree(cached.contentAsByteArray) }.getOrNull()
        return tree?.takeIf { it.isObject }?.path(AGGREGATE_TYPE)
    }

    private companion object {
        const val AGGREGATE_TYPE = "aggregateType"
    }
}
