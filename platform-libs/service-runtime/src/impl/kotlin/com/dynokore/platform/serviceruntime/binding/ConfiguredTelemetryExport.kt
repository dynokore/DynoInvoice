package com.dynokore.platform.serviceruntime.binding

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * The one [TelemetryExport] implementation platform-libs ships (INV-Q-11058, INV-REQ-1384). `bundled-otel` (private,
 * the collector in the umbrella chart) and `managed-observability` (SaaS, the operator's collector) select this same
 * class and differ only in the endpoint the deployment gives (ARC-11).
 *
 * Construction refuses a selection other than the two names, and an endpoint that is missing, blank, not an absolute
 * `http` or `https` URI (scheme compared case-insensitively, RFC 3986 section 3.1) with a host, or that carries user information, a query, a fragment or a trailing slash. The
 * refusal messages name the property and never the value (OWASP Logging Cheat Sheet). The endpoint is returned
 * unchanged.
 */
class ConfiguredTelemetryExport(
    selection: String?,
    endpoint: String?,
) : TelemetryExport {
    private val endpoint: String

    init {
        require(selection in SELECTIONS) {
            "$SELECTION_PROPERTY must be one of ${SELECTIONS.joinToString(", ")}"
        }
        this.endpoint =
            requireNotNull(endpoint?.takeIf { isValid(it) }) {
                "$ENDPOINT_PROPERTY must be an http or https URL with a host and without user information, query, " +
                    "fragment or trailing slash"
            }
    }

    override fun otlpEndpoint(): String = endpoint

    companion object {
        const val SELECTION_PROPERTY = "gst.bindings.telemetry-export"
        const val ENDPOINT_PROPERTY = "gst.bindings.telemetry-export-otlp-endpoint"
        private val SCHEMES = listOf("http", "https")
        private val SELECTIONS = listOf("bundled-otel", "managed-observability")

        private fun isValid(text: String): Boolean {
            if (text.isBlank()) return false
            val uri =
                try {
                    URI(text)
                } catch (_: URISyntaxException) {
                    return false
                }
            return uri.scheme?.lowercase(Locale.ROOT) in SCHEMES &&
                !uri.host.isNullOrEmpty() &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                !text.endsWith("/") &&
                !text.endsWith("?") &&
                !text.endsWith("#")
        }
    }
}
