package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.serviceruntime.jobs.JobErrorCode
import tools.jackson.databind.ObjectMapper

/**
 * The closed service-runtime cases (ARC-19) with their SRT catalogue codes (owner ruling INV-Q-3720, INV-REQ-827
 * b3; the catalogue rows of INV-T-3742, gst-catalogue 1.12.0, SRT-011 in 1.13.0 and SRT-012 in 1.15.0): owner
 * Platform, severity BLOCK, disposition TERMINAL for all twelve (SRT-007 and SRT-008 by coordinator ruling
 * INV-Q-3754, SRT-012 by coordinator ruling INV-Q-4059), each at its single HTTP status (API-21).
 */
enum class ClosedErrorCode(
    val code: String,
    val httpStatus: Int,
    val title: String,
    val disposition: String,
) {
    CONTRACT_VIOLATION("SRT-001", HttpStatusCodes.BAD_REQUEST, "Request does not match the contract", "TERMINAL"),
    IDEMPOTENCY_KEY_INVALID("SRT-002", HttpStatusCodes.BAD_REQUEST, "Idempotency-Key missing or malformed", "TERMINAL"),
    UNAUTHENTICATED("SRT-003", HttpStatusCodes.UNAUTHORIZED, "Not authenticated", "TERMINAL"),
    ACCESS_DENIED("SRT-004", HttpStatusCodes.FORBIDDEN, "Access denied", "TERMINAL"),
    NOT_FOUND("SRT-005", HttpStatusCodes.NOT_FOUND, "No such resource or operation", "TERMINAL"),
    METHOD_NOT_ALLOWED("SRT-006", HttpStatusCodes.METHOD_NOT_ALLOWED, "Method not allowed", "TERMINAL"),
    IN_FLIGHT("SRT-007", HttpStatusCodes.CONFLICT, "Request with this Idempotency-Key in flight", "TERMINAL"),
    STALE_AGGREGATE("SRT-008", HttpStatusCodes.CONFLICT, "Stale aggregate version", "TERMINAL"),
    DIFFERENT_REQUEST(
        "SRT-009",
        HttpStatusCodes.UNPROCESSABLE,
        "Idempotency-Key reused with a different request",
        "TERMINAL",
    ),
    INTERNAL_ERROR("SRT-010", HttpStatusCodes.INTERNAL_ERROR, "Internal error", "TERMINAL"),

    /**
     * The token issuer is unreachable and no cached key verifies the token: retryable, never 401 or 500 (coordinator
     * ruling INV-Q-3773; catalogue row of INV-T-3762, gst-catalogue 1.13.0).
     */
    ISSUER_UNAVAILABLE("SRT-011", HttpStatusCodes.UNAVAILABLE, "Token issuer unavailable", "TERMINAL"),

    /**
     * A protective rate limit is exceeded: the client retries after `Retry-After` (FR-AG.39, API-30; INV-REQ-825 b1
     * and b6, owner ruling INV-Q-4048 A). Emitted by the API gateway's gateway-authz container (INV-REQ-827 b3);
     * render it with [Problems.render] and its Retry-After (catalogue row of INV-T-4055, gst-catalogue 1.15.0;
     * coordinator ruling INV-Q-4059).
     */
    RATE_LIMITED("SRT-012", HttpStatusCodes.TOO_MANY_REQUESTS, "Protective rate limit exceeded", "TERMINAL"),
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
 * INV-Q-3720), so no response is code-less. Every coded response carries `type` `https://errors.<host>/<code>`
 * (API-20, coordinator ruling INV-Q-3796); `about:blank` is only for a response with no code, and none is left
 * here. A server error carries no detail (API-22).
 */
@Suppress("TooManyFunctions") // public one-line problem factories; INV-Q-4629, INV-Q-7495 (CS-50)
object Problems {
    const val CONTENT_TYPE = "application/problem+json"

    /** The response header names [render] sets. */
    const val CONTENT_TYPE_HEADER = "Content-Type"
    const val RETRY_AFTER_HEADER = "Retry-After"

    /**
     * The product-wide host of the error-documentation URIs (API-20): one constant, not configuration
     * (INV-Q-3796), set to the host the published contracts use until owner question INV-Q-3800 is ruled.
     */
    const val ERROR_TYPE_HOST = "invoice.internal"

    /** The documentation URI of catalogue [code], the `type` of every response carrying it (API-20). */
    fun typeOf(code: String): String = "https://errors.$ERROR_TYPE_HOST/$code"

    private const val BLOCK = "BLOCK"
    private val mapper = ObjectMapper()

    private class Catalogued(
        val title: String,
        val owner: String,
    )

    /**
     * Title and remediation owner per JOB code (platform-contracts catalogue/errors/error-codes.yaml); all BLOCK,
     * TERMINAL.
     */
    private val jobCodes =
        mapOf(
            JobErrorCode.JOB_UNKNOWN to Catalogued("Job unknown", "Platform"),
            JobErrorCode.SCOPE_NOT_PERMITTED to
                Catalogued(
                    "Job command not permitted at this scope",
                    "tenant.admin or the form's platform operator role",
                ),
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
    ): StoredResponse =
        coded(
            code.httpStatus,
            code.title,
            detail.takeIf { code.httpStatus < HTTP_SERVER_ERROR },
            correlationId,
            code.code,
            code.disposition,
            code.remediationOwner,
        )

    /**
     * The closed case [code] as a complete response for a caller outside service-runtime's servlet path, such as the
     * API gateway's gateway-authz extAuth denials (401 SRT-003, 503 SRT-011, 429 SRT-012), so they carry the same
     * API-20 envelope as service responses (ARC-19, CS-14; INV-T-4056): the status, the envelope body of [closed],
     * and the headers `Content-Type: application/problem+json` and, for the 429 only, `Retry-After` in delta-seconds
     * (RFC 9110 section 10.2.3; INV-REQ-825 b1). [retryAfterSeconds] is required, and not negative, for a 429 and
     * refused for every other code.
     */
    fun render(
        code: ClosedErrorCode,
        correlationId: String,
        detail: String? = null,
        retryAfterSeconds: Long? = null,
    ): ProblemResponse {
        if (code.httpStatus == HTTP_TOO_MANY_REQUESTS) {
            require(retryAfterSeconds != null && retryAfterSeconds >= 0) {
                "${code.code} (HTTP 429) needs Retry-After in non-negative seconds (INV-REQ-825 b1)"
            }
        } else {
            require(retryAfterSeconds == null) { "Retry-After is only for HTTP 429, not ${code.code}" }
        }
        val response = closed(code, correlationId, detail)
        val headers =
            buildMap {
                put(CONTENT_TYPE_HEADER, CONTENT_TYPE)
                retryAfterSeconds?.let { put(RETRY_AFTER_HEADER, it.toString()) }
            }
        return ProblemResponse(response.status, headers, response.body)
    }

    /**
     * The POL refusal [code] of `setPolicy` and `acknowledgeControlRelaxation`, at the code's single status (API-21).
     */
    fun policy(
        code: PolicyErrorCode,
        correlationId: String,
    ): StoredResponse = coded(code.httpStatus, code.title, null, correlationId, code.code, "TERMINAL", code.owner.text)

    /**
     * The TNC refusal [code] as a complete response (INV-Q-7553 A): status 409, the headers of [render] for a non-429
     * code (`Content-Type: application/problem+json`, no `Retry-After`) and the API-20 envelope, with [detail] as a
     * client error carries it (API-22).
     */
    fun renderTenancy(
        code: TenancyErrorCode,
        correlationId: String,
        detail: String? = null,
    ): ProblemResponse {
        val response = coded(code.httpStatus, code.title, detail, correlationId, code.code, "TERMINAL", code.owner.text)
        return ProblemResponse(response.status, mapOf(CONTENT_TYPE_HEADER to CONTENT_TYPE), response.body)
    }

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
        disposition: String,
        owner: String,
    ): StoredResponse {
        val node =
            mapper.createObjectNode().apply {
                put("type", typeOf(code))
                put("title", title)
                put("status", status)
                detail?.let { put("detail", it) }
                put("correlationId", correlationId)
                put("code", code)
                put("severity", BLOCK)
                put("disposition", disposition)
                put("remediationOwner", owner)
            }
        return StoredResponse(status, mapper.writeValueAsString(node))
    }

    private const val HTTP_SERVER_ERROR = 500
    private const val HTTP_TOO_MANY_REQUESTS = 429
}

/** An error response rendered by [Problems.render]: its status, its response headers and its API-20 envelope body. */
data class ProblemResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: String,
)
