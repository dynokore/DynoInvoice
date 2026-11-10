package com.dynokore.gst.controlPlane.adapters.http

import com.dynokore.platform.serviceruntime.http.ProblemResponse
import com.dynokore.platform.serviceruntime.http.Problems
import tools.jackson.databind.json.JsonMapper

/**
 * The refusals of the tenant schedule operations, carrying the codes the catalogue publishes and the contract declares
 * as `x-error-codes` (INV-T-19764): [CONFLICT_CODE] CPL-010 with 409 and [RULE_BROKEN_CODE] CPL-011 with 422. Platform
 * libs has no CPL enum, so the strings are held here; `ScheduleProblemsCatalogueTest` pins each code, title, severity,
 * disposition and owner to the row of the pinned gst-catalogue. Same envelope shape as [GateRunProblems]; the body
 * carries the refusal code of the rule in `errors[]`.
 */
internal object ScheduleProblems {
    const val CONFLICT_CODE = "CPL-010"
    const val RULE_BROKEN_CODE = "CPL-011"
    const val CONFLICT_TITLE = "Tenant schedule conflict"
    const val RULE_BROKEN_TITLE = "Tenant schedule not valid"
    const val SEVERITY = "BLOCK"
    const val DISPOSITION = "TERMINAL"
    const val OWNER = "Platform"
    private const val CONFLICT = 409
    private const val UNPROCESSABLE = 422
    private val json = JsonMapper.builder().build()

    fun conflict(
        rule: String,
        correlationId: String,
    ) = render(CONFLICT_CODE, CONFLICT, CONFLICT_TITLE, rule, correlationId)

    fun ruleBroken(
        rule: String,
        correlationId: String,
    ) = render(RULE_BROKEN_CODE, UNPROCESSABLE, RULE_BROKEN_TITLE, rule, correlationId)

    private fun render(
        code: String,
        status: Int,
        title: String,
        rule: String,
        correlationId: String,
    ): ProblemResponse {
        val body =
            linkedMapOf(
                "type" to Problems.typeOf(code),
                "title" to title,
                "status" to status,
                "code" to code,
                "severity" to SEVERITY,
                "disposition" to DISPOSITION,
                "remediationOwner" to OWNER,
                "errors" to listOf(linkedMapOf("code" to code, "field" to "/", "message" to rule)),
                "correlationId" to correlationId,
            )
        return ProblemResponse(
            status,
            mapOf(Problems.CONTENT_TYPE_HEADER to Problems.CONTENT_TYPE),
            json.writeValueAsString(body),
        )
    }
}
