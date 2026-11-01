package com.dynokore.gst.transport.adapters.http

import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException

/**
 * A body that does not parse or break the schema of `ApNotification` (a `kind` outside the contract's enum, a missing
 * required field, a bad length) is 400 SRT-001 with the API-20 envelope. Scoped to [NotificationsController]; it runs
 * only after the signature verified, because the filter refuses an unsigned request first.
 */
@RestControllerAdvice(assignableTypes = [NotificationsController::class])
class ApNotificationAdvice(
    private val refusals: ApiRefusalAdvice,
) {
    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MethodArgumentNotValidException::class,
        HandlerMethodValidationException::class,
    )
    fun contractViolation(): ResponseEntity<String> = refusals.refuse(ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION))
}
