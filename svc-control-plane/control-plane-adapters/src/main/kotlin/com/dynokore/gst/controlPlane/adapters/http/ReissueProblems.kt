package com.dynokore.gst.controlPlane.adapters.http

import com.dynokore.platform.serviceruntime.http.ProblemResponse
import com.dynokore.platform.serviceruntime.http.Problems
import tools.jackson.databind.json.JsonMapper

/**
 * The refusals of requestFirstAdministratorInvitationReissue that the catalogue names (INV-Q-16202 A, INV-Q-16204 A):
 * CPL-008 (409, access-svc refused the reissue or the provisioning request does not read CREATED) and CPL-009 (503,
 * access-svc gave no answer in time or answered 503). Platform-libs has no CPL enum, so the codes are held here,
 * written
 * like [RegionNotListed.CODE]. The body carries no detail: nothing of the access answer is echoed.
 */
internal object ReissueProblems {
    const val NOT_REISSUABLE = "CPL-008"
    const val ACCESS_UNAVAILABLE = "CPL-009"
    private const val CONFLICT = 409
    private const val UNAVAILABLE = 503
    private val json = JsonMapper.builder().build()

    fun statusOf(code: String): Int =
        when (code) {
            NOT_REISSUABLE -> CONFLICT
            ACCESS_UNAVAILABLE -> UNAVAILABLE
            else -> error("$code is not a reissue refusal")
        }

    private fun titleOf(code: String): String =
        when (code) {
            NOT_REISSUABLE -> "First administrator invitation cannot be reissued"
            else -> "Access service unavailable"
        }

    private fun messageOf(code: String): String =
        when (code) {
            NOT_REISSUABLE -> "The invitation cannot be reissued for this provisioning request"
            else -> "The access service did not answer; retry with the same Idempotency-Key"
        }

    fun render(
        code: String,
        correlationId: String,
    ): ProblemResponse {
        val status = statusOf(code)
        val body =
            linkedMapOf(
                "type" to Problems.typeOf(code),
                "title" to titleOf(code),
                "status" to status,
                "code" to code,
                "severity" to "BLOCK",
                "disposition" to "TERMINAL",
                "remediationOwner" to "Platform",
                "errors" to listOf(linkedMapOf("code" to code, "field" to "/", "message" to messageOf(code))),
                "correlationId" to correlationId,
            )
        return ProblemResponse(
            status,
            mapOf(Problems.CONTENT_TYPE_HEADER to Problems.CONTENT_TYPE),
            json.writeValueAsString(body),
        )
    }
}
