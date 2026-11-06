package com.dynokore.gst.controlPlane.adapters.http

import com.dynokore.platform.serviceruntime.http.ProblemResponse
import com.dynokore.platform.serviceruntime.http.Problems
import tools.jackson.databind.json.JsonMapper

/**
 * The refusals of recordGateRun that the catalogue names (INV-Q-14041 A): CPL-004 (409, the train is already
 * published), CPL-005 (409, the key holds a record that is not a repeat) and CPL-006 (422, the record breaks a gate
 * rule), and of concludeAccreditationTask: CPL-007 (409, the task is concluded with another conclusion;
 * INV-Q-15428 A, INV-Q-15478 A). Each is the API-20 envelope with `errors[]` naming the code, the field and a
 * message; the shape of CPL-003.
 * Platform-libs has no CPL enum, so the codes are held here.
 */
internal object GateRunProblems {
    const val TRAIN_PUBLISHED = "CPL-004"
    const val RECORD_DIFFERS = "CPL-005"
    const val RULE_BROKEN = "CPL-006"
    const val CONCLUSION_DIFFERS = "CPL-007"
    private const val CONFLICT = 409
    private const val UNPROCESSABLE = 422
    private val json = JsonMapper.builder().build()

    fun statusOf(code: String): Int =
        when (code) {
            TRAIN_PUBLISHED, RECORD_DIFFERS, CONCLUSION_DIFFERS -> CONFLICT
            RULE_BROKEN -> UNPROCESSABLE
            else -> error("$code is not a recordGateRun refusal")
        }

    private fun titleOf(code: String): String =
        when (code) {
            TRAIN_PUBLISHED -> "Release train already published"
            RECORD_DIFFERS -> "Gate run already recorded with a different result"
            CONCLUSION_DIFFERS -> "Accreditation task already concluded with a different conclusion"
            else -> "Gate run breaks a gate rule"
        }

    fun render(
        code: String,
        field: String,
        message: String,
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
                "errors" to listOf(linkedMapOf("code" to code, "field" to field, "message" to message)),
                "correlationId" to correlationId,
            )
        return ProblemResponse(
            status,
            mapOf(Problems.CONTENT_TYPE_HEADER to Problems.CONTENT_TYPE),
            json.writeValueAsString(body),
        )
    }
}
