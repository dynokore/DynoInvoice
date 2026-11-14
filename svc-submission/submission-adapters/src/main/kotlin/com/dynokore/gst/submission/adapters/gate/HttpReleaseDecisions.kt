package com.dynokore.gst.submission.adapters.gate

import com.dynokore.gst.submission.adapters.clients.access.api.SeparationOfDutyApi
import com.dynokore.gst.submission.application.gate.ReleaseDecisions
import com.dynokore.gst.submission.domain.gate.ReleaseOutcome
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestClient

/**
 * [ReleaseDecisions] over `decideOverrideRelease` of access-svc, through the client generated from its contract
 * (STK-46, INV-Q-10695). A filing-gate override sends no document reference, version or total (INV-Q-10721).
 *
 * The answer is read as raw strings and mapped by exact string equality in [ReleaseOutcome.from]: the generated model
 * is not used for it, because Jackson trims an enum string (`"ALLOW "` would read as ALLOW) and so cannot fail closed.
 * Fail closed: an error, a timeout, a status other than 200, an unreadable answer or a member that is not a string is
 * [ReleaseOutcome.Denied].
 */
class HttpReleaseDecisions(
    client: RestClient,
) : ReleaseDecisions {
    /** The generated operation, with the answer left as the members of the JSON object. */
    private class RawApi(
        client: RestClient,
    ) : SeparationOfDutyApi(client) {
        fun call(
            entityRef: String,
            errorCode: String,
            approverRef: String,
            priorApproverRef: List<String>?,
        ): ResponseEntity<Map<String, Any?>> =
            request<Unit, Map<String, Any?>>(
                decideOverrideReleaseRequestConfig(
                    entityRef = entityRef,
                    errorCode = errorCode,
                    approverRef = approverRef,
                    priorApproverRef = priorApproverRef,
                ),
            )
    }

    private val api = RawApi(client)

    override fun decide(
        entityRef: String,
        errorCode: String,
        approverRef: String,
        priorApproverRefs: List<String>,
    ): ReleaseOutcome =
        try {
            val response = api.call(entityRef, errorCode, approverRef, priorApproverRefs.takeIf { it.isNotEmpty() })
            val body = response.body
            if (response.statusCode.value() == OK && body != null) {
                ReleaseOutcome.from(
                    body["decision"] as? String,
                    body["outcome"] as? String,
                    body["reasonCode"] as? String,
                )
            } else {
                denied()
            }
        } catch (
            @Suppress("TooGenericExceptionCaught", "SwallowedException") failure: Exception,
        ) {
            // Fail closed: any failure of the call is a denial; the caller learns only that the release is refused.
            denied()
        }

    private fun denied() = ReleaseOutcome.Denied(ReleaseOutcome.NOT_OVERRIDABLE)

    private companion object {
        const val OK = 200
    }
}
