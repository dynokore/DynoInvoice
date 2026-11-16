package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.RelayedRefusal
import com.dynokore.platform.serviceruntime.http.StoredResponse

/**
 * The refusals of overrideException (RFC 9457): the closed answers, the owner's refusal relayed through the one
 * platform builder with the correlation id of the request being served (never the owner's), and the closed 503
 * SRT-013 for an unavailable owner, with no member of the owner's text.
 */
class OverridesProblemMapper {
    fun notFound(correlationId: String): StoredResponse = Problems.notFound(correlationId)

    fun accessDenied(correlationId: String): StoredResponse = Problems.accessDenied(correlationId)

    fun unauthenticated(correlationId: String): StoredResponse = Problems.unauthenticated(correlationId)

    fun contractViolation(
        detail: String,
        correlationId: String,
    ): StoredResponse = Problems.contractViolation(detail, correlationId)

    fun unavailable(correlationId: String): StoredResponse =
        Problems.render(ClosedErrorCode.SERVICE_NOT_READY, correlationId).let { StoredResponse(it.status, it.body) }

    fun relayed(
        refusal: OwnerRefusal,
        correlationId: String,
    ): StoredResponse =
        stored(
            Problems.renderRelayed(
            RelayedRefusal(
                status = refusal.status,
                code = refusal.code,
                title = refusal.title,
                severity = refusal.severity,
                disposition = refusal.disposition,
                remediationOwner = refusal.remediationOwner,
                detail = refusal.detail,
            ),
                correlationId,
            ),
        )

    private fun stored(response: com.dynokore.platform.serviceruntime.http.ProblemResponse) =
        StoredResponse(response.status, response.body)
}

/** Writes the refusals of [OverridesController] as application/problem+json. */
@org.springframework.web.bind.annotation.RestControllerAdvice(assignableTypes = [OverridesController::class])
class OverridesProblemAdvice {
    @org.springframework.web.bind.annotation.ExceptionHandler(OverridesRefusal::class)
    fun refusal(refusal: OverridesRefusal) = write(refusal.answer)

    /** A body the generated model refuses to read: no reason, or a wrong type. */
    @org.springframework.web.bind.annotation.ExceptionHandler(
        org.springframework.http.converter.HttpMessageNotReadableException::class,
        org.springframework.web.bind.MethodArgumentNotValidException::class,
        org.springframework.web.method.annotation.HandlerMethodValidationException::class,
    )
    fun invalidBody() = write(Problems.contractViolation("the request body is not valid", correlation()))

    private fun write(answer: StoredResponse) =
        org.springframework.http.ResponseEntity
            .status(answer.status)
            .header("Content-Type", Problems.CONTENT_TYPE)
            .body(answer.body)

    private fun correlation(): String =
        com.dynokore.platform.serviceruntime.logging.CorrelationContext
            .currentCorrelationId()
            ?.value ?: "http:${java.util.UUID.randomUUID()}"
}
