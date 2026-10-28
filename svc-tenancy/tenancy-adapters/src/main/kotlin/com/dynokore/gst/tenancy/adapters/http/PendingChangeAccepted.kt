package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.contract.model.PendingElectionChange
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * A change of an election already recorded was stored PENDING (INV-Q-10105). The generated operation returns the 201
 * body type, so the 202 answer of the contract (`PendingElectionChange`) leaves the controller as this signal; the
 * request's transaction has committed by then and nothing is rolled back.
 */
class PendingChangeAccepted(
    val change: PendingElectionChange,
) : RuntimeException("election change ${change.changeRef} is pending approval")

/** Renders [PendingChangeAccepted] as 202 with the contract body. */
@RestControllerAdvice
class PendingChangeAdvice {
    @ExceptionHandler(PendingChangeAccepted::class)
    fun accepted(signal: PendingChangeAccepted): ResponseEntity<PendingElectionChange> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(signal.change)
}
