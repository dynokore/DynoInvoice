package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import tools.jackson.databind.json.JsonMapper

/**
 * The API-20 envelope (RFC 9457 `application/problem+json`) of a catalogue row that service-runtime's closed enums do
 * not carry. Every field comes from the row: `code`, `title` (the row's condition), `severity`, `disposition` and
 * `remediationOwner` (the row's owner); `type` is [Problems.typeOf]. A server error (status 500 and above) carries no
 * detail (API-22).
 */
internal object CatalogueEnvelope {
    private val mapper = JsonMapper.builder().build()
    private const val SERVER_ERROR = 500

    fun render(
        refusal: CatalogueRefusal,
        correlationId: String,
    ): String =
        mapper.writeValueAsString(
            mapper.createObjectNode().apply {
                put("type", Problems.typeOf(refusal.code.code))
                put("title", refusal.code.condition)
                put("status", refusal.status)
                refusal.detail?.takeIf { refusal.status < SERVER_ERROR }?.let { put("detail", it) }
                put("correlationId", correlationId)
                put("code", refusal.code.code)
                put("severity", refusal.code.severity.name)
                put("disposition", refusal.code.disposition?.name ?: "TERMINAL")
                put("remediationOwner", refusal.code.owner)
            },
        )

    /** The catalogue row of exactly [text], or null: `MDM-0160` and `mdm-016` are not rows. */
    fun row(text: String): ErrorCode? = ErrorCode.fromCode(text)
}
