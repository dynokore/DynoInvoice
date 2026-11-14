package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import java.util.UUID

/** Writes the refusals of [DeferralsController] as application/problem+json. */
@RestControllerAdvice(assignableTypes = [DeferralsController::class])
class DeferralsProblemAdvice {
    @ExceptionHandler(DeferralsRefusal::class)
    fun refusal(refusal: DeferralsRefusal): ResponseEntity<String> = write(refusal.answer)

    /** A body the generated model refuses to read, an unknown target kind included (the enum is closed on the wire). */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadableBody(): ResponseEntity<String> =
        write(Problems.contractViolation("the request body is not valid", correlation()))

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun invalidParameter(): ResponseEntity<String> =
        write(Problems.contractViolation("a request member is out of bounds", correlation()))

    /** Jakarta's ConstraintViolationException is matched by class name (no compile dependency); others are rethrown. */
    @ExceptionHandler(RuntimeException::class)
    fun constraintViolation(failure: RuntimeException): ResponseEntity<String> {
        if (failure.javaClass.name != CONSTRAINT_VIOLATION) throw failure
        return invalidParameter()
    }

    private fun write(answer: StoredResponse): ResponseEntity<String> =
        ResponseEntity
            .status(answer.status)
            .header("Content-Type", Problems.CONTENT_TYPE)
            .body(answer.body)

    private companion object {
        const val CONSTRAINT_VIOLATION = "jakarta.validation.ConstraintViolationException"
    }

    private fun correlation(): String = CorrelationContext.currentCorrelationId()?.value ?: "http:${UUID.randomUUID()}"
}
