package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.serviceruntime.jobs.JobErrorCode
import tools.jackson.databind.ObjectMapper

/**
 * The closed service-runtime cases (ARC-19) with their SRT catalogue codes (owner ruling INV-Q-3720, INV-REQ-827
 * b3; the catalogue rows of INV-T-3742, invoice-docs !8, platform-contracts !40 at gst-catalogue 1.12.0): owner
 * Platform, severity BLOCK, each at its single HTTP status (API-21). [disposition] is inlined only where it is
 * settled: the disposition of SRT-007 and SRT-008 is still being ruled, so those responses leave it to the
 * catalogue lookup by `code` (the envelope keeps it optional, CS-13).
 */
enum class ClosedErrorCode(
    val code: String,
    val httpStatus: Int,
    val title: String,
    val disposition: String?,
) {
    CONTRACT_VIOLATION("SRT-001", 400, "Request does not match the contract", "TERMINAL"),
    IDEMPOTENCY_KEY_INVALID("SRT-002", 400, "Idempotency-Key missing or malformed", "TERMINAL"),
    UNAUTHENTICATED("SRT-003", 401, "Not authenticated", "TERMINAL"),
    ACCESS_DENIED("SRT-004", 403, "Access denied", "TERMINAL"),
    NOT_FOUND("SRT-005", 404, "No such resource or operation", "TERMINAL"),
    METHOD_NOT_ALLOWED("SRT-006", 405, "Method not allowed", "TERMINAL"),
    IN_FLIGHT("SRT-007", 409, "Request with this Idempotency-Key in flight", null),
    STALE_AGGREGATE("SRT-008", 409, "Stale aggregate version", null),
    DIFFERENT_REQUEST("SRT-009", 422, "Idempotency-Key reused with a different request", "TERMINAL"),
    INTERNAL_ERROR("SRT-010", 500, "Internal error", "TERMINAL"),
    ;

    /** Every SRT code is remediated by the platform (error-codes.yaml `owner`). */
    val remediationOwner: String get() = "Platform"
}

/**
 * The API-20 error envelope (RFC 9457 `application/problem+json`, platform-contracts
 * `service-runtime/schemas/error-envelope.schema.json`) of the operations service-runtime serves, mapped in one
 * place (ARC-19, CS-14). Every response carries its catalogue code, severity, disposition and remediation owner
 * at the code's single status (API-21, INV-REQ-827): the JOB codes (catalogue gst-catalogue 1.5.0) for a refused
 * job command, the SRT codes of [ClosedErrorCode] for the closed service-runtime cases (owner ruling
 * INV-Q-3720), so no response is code-less. A server error carries no detail (API-22).
 */
object Problems {
    const val CONTENT_TYPE = "application/problem+json"
    private const val ABOUT_BLANK = "about:blank"
    private const val BLOCK = "BLOCK"
    private val mapper = ObjectMapper()

    private class Catalogued(val title: String, val owner: String)

    /** Title and remediation owner per JOB code (platform-contracts catalogue/errors/error-codes.yaml); all BLOCK, TERMINAL. */
    private val jobCodes =
        mapOf(
            JobErrorCode.JOB_UNKNOWN to Catalogued("Job unknown", "Platform"),
            JobErrorCode.SCOPE_NOT_PERMITTED to
                Catalogued("Job command not permitted at this scope", "tenant.admin or the form's platform operator role"),
            JobErrorCode.PAUSE_PAST_DEADLINE to Catalogued("Pause refused: past the deadline bound", "Integration"),
            JobErrorCode.PAUSE_END_NOT_FUTURE to Catalogued("Pause end time invalid", "Integration"),
        )

    fun job(
        code: JobErrorCode,
        correlationId: String,
    ): StoredResponse {
        val entry = jobCodes.getValue(code)
        return coded(code.httpStatus, entry.title, null, correlationId, code.code, "TERMINAL", entry.owner)
    }

    /** The closed case [code], with [detail] only for a client error (API-22: a server error carries none). */
    fun closed(
        code: ClosedErrorCode,
        correlationId: String,
        detail: String? = null,
    ): StoredResponse = coded(
        code.httpStatus,
        code.title,
        detail.takeIf { code.httpStatus < HTTP_SERVER_ERROR },
        correlationId,
        code.code,
        code.disposition,
        code.remediationOwner,
    )

    fun contractViolation(
        detail: String,
        correlationId: String,
    ) = closed(ClosedErrorCode.CONTRACT_VIOLATION, correlationId, detail)

    fun idempotencyKeyInvalid(
        detail: String,
        correlationId: String,
    ) = closed(ClosedErrorCode.IDEMPOTENCY_KEY_INVALID, correlationId, detail)

    fun unauthenticated(correlationId: String) = closed(ClosedErrorCode.UNAUTHENTICATED, correlationId)

    fun accessDenied(correlationId: String) = closed(ClosedErrorCode.ACCESS_DENIED, correlationId)

    fun notFound(correlationId: String) = closed(ClosedErrorCode.NOT_FOUND, correlationId)

    fun methodNotAllowed(correlationId: String) = closed(ClosedErrorCode.METHOD_NOT_ALLOWED, correlationId)

    fun inFlight(correlationId: String) = closed(ClosedErrorCode.IN_FLIGHT, correlationId)

    fun differentRequest(correlationId: String) = closed(ClosedErrorCode.DIFFERENT_REQUEST, correlationId)

    fun serverError(correlationId: String) = closed(ClosedErrorCode.INTERNAL_ERROR, correlationId)

    @Suppress("LongParameterList")
    private fun coded(
        status: Int,
        title: String,
        detail: String?,
        correlationId: String,
        code: String,
        disposition: String?,
        owner: String,
    ): StoredResponse {
        val node =
            mapper.createObjectNode().apply {
                put("type", ABOUT_BLANK)
                put("title", title)
                put("status", status)
                detail?.let { put("detail", it) }
                put("correlationId", correlationId)
                put("code", code)
                put("severity", BLOCK)
                disposition?.let { put("disposition", it) }
                put("remediationOwner", owner)
            }
        return StoredResponse(status, mapper.writeValueAsString(node))
    }

    private const val HTTP_SERVER_ERROR = 500
}
