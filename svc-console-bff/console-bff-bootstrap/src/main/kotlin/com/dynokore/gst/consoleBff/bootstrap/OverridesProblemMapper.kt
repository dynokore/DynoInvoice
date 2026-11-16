package com.dynokore.gst.consoleBff.bootstrap

import com.dynokore.gst.consoleBff.application.override.OwnerRefusal
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.http.RelayedRefusal
import com.dynokore.platform.serviceruntime.http.StoredResponse

/**
 * The refusals of overrideException (RFC 9457): the closed answers, the owner's refusal relayed through the one
 * platform builder with the correlation id of the request being served (never the owner's), and the closed 503
 * SRT-013 for an unavailable owner, with no member of the owner's text.
 */
class OverridesProblemMapper {
    fun notFound(correlationId: String): StoredResponse = Problems.notFound(correlationId)

    fun accessDenied(correlationId: String): StoredResponse = Problems.accessDenied(correlationId)

    fun unauthenticated(correlationId: String): StoredResponse = Problems.unauthenticated(correlationId)

    fun contractViolation(
        detail: String,
        correlationId: String,
    ): StoredResponse = Problems.contractViolation(detail, correlationId)

    fun unavailable(correlationId: String): StoredResponse =
        Problems.render(ClosedErrorCode.SERVICE_NOT_READY, correlationId).let { StoredResponse(it.status, it.body) }

    fun relayed(
        refusal: OwnerRefusal,
        correlationId: String,
    ): StoredResponse =
        stored(
            Problems.renderRelayed(
                RelayedRefusal(
                    status = refusal.status,
                    code = refusal.code,
                    title = refusal.title,
                    severity = refusal.severity,
                    disposition = refusal.disposition,
                    remediationOwner = refusal.remediationOwner,
                    detail = refusal.detail,
                ),
                correlationId,
            ),
        )

    private fun stored(response: com.dynokore.platform.serviceruntime.http.ProblemResponse) =
        StoredResponse(response.status, response.body)
}
