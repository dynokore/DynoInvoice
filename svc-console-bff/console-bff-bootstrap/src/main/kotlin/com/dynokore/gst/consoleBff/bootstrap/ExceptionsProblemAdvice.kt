package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import java.util.UUID

/**
 * Writes the refusals of [ExceptionsController] as application/problem+json. A parameter the contract bounds
 * (limit above its maximum, entityRef over 40 characters) fails the generated validation: a contract violation too.
 */
@RestControllerAdvice(assignableTypes = [ExceptionsController::class])
class ExceptionsProblemAdvice {
    @ExceptionHandler(ExceptionsRefusal::class)
    fun refusal(refusal: ExceptionsRefusal): ResponseEntity<String> = write(refusal.answer)

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun invalidParameter(): ResponseEntity<String> =
        write(Problems.contractViolation("a request parameter is out of bounds", correlation()))

    /**
     * The method validation of the generated `@Validated` interface throws jakarta's ConstraintViolationException;
     * bootstrap has no compile dependency on jakarta.validation (no new dependency), so it is matched by its class name
     * and every other runtime exception is rethrown unchanged.
     */
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
