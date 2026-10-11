package com.dynokore.platform.serviceruntime.http

import com.dynokore.platform.serviceruntime.jobs.JobErrorCode
import tools.jackson.databind.ObjectMapper

/**
 * The API-20 error envelope (RFC 9457 `application/problem+json`, platform-contracts
 * `service-runtime/schemas/error-envelope.schema.json`) of the operations service-runtime serves, mapped in one
 * place (ARC-19, CS-14). A catalogue code carries its code, severity, disposition and remediation owner
 * (catalogue gst-catalogue 1.5.0) at the code's single status (API-21). The closed service-runtime cases
 * (`AccessDenied` 403, `IdempotencyConflict` 409 and 422, `ContractViolation` 400, plus 401, 404 and 405 of the
 * transport) stay code-less with type `about:blank` (ARC-19, INV-API-3023, INV-API-3025). A server error carries
 * no detail (API-22).
 */
object Problems {
    const val CONTENT_TYPE = "application/problem+json"
    private const val ABOUT_BLANK = "about:blank"
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
        val node = base(code.httpStatus, entry.title, null, correlationId)
        node.put("code", code.code)
        node.put("severity", "BLOCK")
        node.put("disposition", "TERMINAL")
        node.put("remediationOwner", entry.owner)
        return StoredResponse(code.httpStatus, mapper.writeValueAsString(node))
    }

    fun contractViolation(
        detail: String,
        correlationId: String,
    ) = closed(HTTP_BAD_REQUEST, "Request does not match the contract", detail, correlationId)

    fun unauthenticated(correlationId: String) = closed(HTTP_UNAUTHORIZED, "Not authenticated", null, correlationId)

    fun accessDenied(correlationId: String) = closed(HTTP_FORBIDDEN, "Outside the caller's scope", null, correlationId)

    fun notFound(correlationId: String) = closed(HTTP_NOT_FOUND, "No such operation", null, correlationId)

    fun methodNotAllowed(correlationId: String) = closed(HTTP_METHOD_NOT_ALLOWED, "Method not allowed", null, correlationId)

    fun inFlight(correlationId: String) = closed(HTTP_CONFLICT, "Request with this key in progress", null, correlationId)

    fun differentRequest(correlationId: String) = closed(HTTP_UNPROCESSABLE, "Idempotency-Key reused with a different request", null, correlationId)

    fun serverError(correlationId: String) = closed(HTTP_SERVER_ERROR, "Internal error", null, correlationId)

    private fun closed(
        status: Int,
        title: String,
        detail: String?,
        correlationId: String,
    ) = StoredResponse(status, mapper.writeValueAsString(base(status, title, detail, correlationId)))

    private fun base(
        status: Int,
        title: String,
        detail: String?,
        correlationId: String,
    ) = mapper.createObjectNode().apply {
        put("type", ABOUT_BLANK)
        put("title", title)
        put("status", status)
        detail?.let { put("detail", it) }
        put("correlationId", correlationId)
    }

    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_METHOD_NOT_ALLOWED = 405
    private const val HTTP_CONFLICT = 409
    private const val HTTP_UNPROCESSABLE = 422
    private const val HTTP_SERVER_ERROR = 500
}
