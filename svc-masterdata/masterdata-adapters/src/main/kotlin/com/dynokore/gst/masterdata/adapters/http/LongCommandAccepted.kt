package com.dynokore.gst.masterdata.adapters.http

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** The 202 answer of a long command (API-17): the RUNNING status as body, `Location` and `Retry-After`. */
class LongCommandAccepted(
    val body: Any,
    val location: String,
    val retryAfterSeconds: Long,
) : RuntimeException("202: the work continues asynchronously", null, false, false)

/** Renders [LongCommandAccepted] as the 202 answer of API-17. */
@RestControllerAdvice
class LongCommandAdvice {
    @ExceptionHandler(LongCommandAccepted::class)
    fun accepted(accepted: LongCommandAccepted): ResponseEntity<Any> =
        ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .header(HttpHeaders.LOCATION, accepted.location)
            .header(HttpHeaders.RETRY_AFTER, accepted.retryAfterSeconds.toString())
            .body(accepted.body)
}
