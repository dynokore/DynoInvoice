package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.contract.model.PendingIdentityTripleChange
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.MasterdataErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
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

/** A refusal with a masterdata catalogue code (MDM-005, answered 422), rendered by the service-runtime renderer. */
class MasterdataRefusal(
    val code: MasterdataErrorCode,
    val detail: String? = null,
) : RuntimeException("${code.code}: ${detail ?: code.title}")

/** The 202 answer of `configureIdentityTriple`, carried to [ApiRefusalAdvice] where the compiler types its body. */
class ProposalAccepted(
    val body: PendingIdentityTripleChange,
) : RuntimeException("202: the change is pending confirmation", null, false, false)

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

    @ExceptionHandler(ProposalAccepted::class)
    fun accepted(accepted: ProposalAccepted): ResponseEntity<PendingIdentityTripleChange> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(accepted.body)

    @ExceptionHandler(MasterdataRefusal::class)
    fun refuse(refusal: MasterdataRefusal): ResponseEntity<String> {
        val problem =
            Problems.renderMasterdata(
                refusal.code,
                CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString(),
                refusal.detail,
            )
        val headers = HttpHeaders()
        problem.headers.forEach { (name, value) -> headers.add(name, value) }
        return ResponseEntity.status(problem.status).headers(headers).body(problem.body)
    }
}
