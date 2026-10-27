package com.dynokore.gst.controlPlane.adapters.http

import com.dynokore.gst.controlPlane.domain.commercial.RegionKey
import com.dynokore.platform.serviceruntime.http.ProblemResponse
import com.dynokore.platform.serviceruntime.http.Problems
import tools.jackson.databind.json.JsonMapper

/**
 * The 422 of a `dataRegion` that is not on the region list (CPL-003, INV-REQ-1306, owner ruling INV-Q-3756 B):
 * the API-20 envelope with `errors[]` naming the field `/dataRegion`.
 */
internal object RegionNotListed {
    const val CODE = "CPL-003"
    private const val STATUS = 422
    private val json = JsonMapper.builder().build()

    fun render(
        region: RegionKey,
        correlationId: String,
    ): ProblemResponse {
        val body =
            linkedMapOf(
                "type" to Problems.typeOf(CODE),
                "title" to "Data region not on the region list",
                "status" to STATUS,
                "code" to CODE,
                "severity" to "BLOCK",
                "disposition" to "TERMINAL",
                "remediationOwner" to "Platform",
                "errors" to
                    listOf(
                        linkedMapOf(
                            "code" to CODE,
                            "field" to "/dataRegion",
                            "message" to "dataRegion ${region.value} is not on the platform's region list",
                        ),
                    ),
                "correlationId" to correlationId,
            )
        return ProblemResponse(
            STATUS,
            mapOf(Problems.CONTENT_TYPE_HEADER to Problems.CONTENT_TYPE),
            json.writeValueAsString(body),
        )
    }
}
