package com.dynokore.gst.tenancy.adapters.http

import com.dynokore.gst.tenancy.domain.CodeRefusal
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.PolicyErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.TenancyErrorCode
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * A refused request: the closed service-runtime case and, optionally, what the caller may be told (never a server
 * detail, API-22).
 */
class ApiRefusal(
    val code: ClosedErrorCode,
    val detail: String? = null,
) : RuntimeException("${code.code}: ${detail ?: code.title}")

/** A business-conflict refusal with its tenancy catalogue code (TNC-001..004), rendered by `Problems.renderTenancy`. */
class TenancyRefusal(
    val code: TenancyErrorCode,
    val detail: String? = null,
) : RuntimeException("${code.code}: ${detail ?: code.title}")

/** A value refused by a check of the owning service: 422 POL-014 (API-21), with the reason the caller may be told. */
class ConstraintRefusal(
    val detail: String,
) : RuntimeException("POL-014: $detail")

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

    @ExceptionHandler(ConstraintRefusal::class)
    fun refuse(refusal: ConstraintRefusal): ResponseEntity<String> {
        val base =
            Problems.policy(
                PolicyErrorCode.CONSTRAINT_REFUSED,
                CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString(),
            )
        val envelope = (mapper.readTree(base.body) as ObjectNode).put("detail", refusal.detail)
        return ResponseEntity
            .status(base.status)
            .header(HttpHeaders.CONTENT_TYPE, Problems.CONTENT_TYPE)
            .body(mapper.writeValueAsString(envelope))
    }

    @ExceptionHandler(TenancyRefusal::class)
    fun refuse(refusal: TenancyRefusal): ResponseEntity<String> {
        val problem =
            Problems.renderTenancy(
                refusal.code,
                CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString(),
                refusal.detail,
            )
        val headers = HttpHeaders()
        problem.headers.forEach { (name, value) -> headers.add(name, value) }
        return ResponseEntity.status(problem.status).headers(headers).body(problem.body)
    }
}

private val mapper = ObjectMapper()

/** The refusals the tenancy operations share (SRT-001 for a malformed request, SRT-005 for an absent node). */
internal fun invalid(message: String): Nothing = throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, message)

internal fun notFound(): Nothing = throw ApiRefusal(ClosedErrorCode.NOT_FOUND)

/** The contract answers 409 for a conflict with the tenant's state; the code is a TNC catalogue code (INV-Q-7553 A). */
internal fun conflict(
    code: TenancyErrorCode,
    message: String,
): Nothing = throw TenancyRefusal(code, message)

/** A repeated status with other values: TNC-004 (INV-Q-8735 A). */
internal fun statusConflict(): Nothing =
    conflict(
        TenancyErrorCode.STATUS_RECORD_CONFLICT,
        "a status of this kind is already recorded from this date with other values",
    )

internal fun shortCodeConflict(reason: CodeRefusal): Nothing =
    if (reason == CodeRefusal.USED_IN_AGGREGATE_NUMBER) {
        conflict(TenancyErrorCode.SHORT_CODE_FIXED, "the short code is fixed by an aggregate number")
    } else {
        conflict(TenancyErrorCode.SHORT_CODE_ALREADY_USED, "the short code is already used in the tenant")
    }

/** 422 POL-014 for an election the checks of the owning service refuse (the contract's x-error-codes). */
internal fun constraintRefused(reason: String): Nothing = throw ConstraintRefusal(reason)

/** 403 SRT-004 for a caller, confirmer or approver who lacks the role, is the caller or cannot be proven. */
internal fun forbidden(reason: String): Nothing = throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED, reason)
