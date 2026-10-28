package com.dynokore.gst.gatewayauthz.check

/**
 * What gateway-authz reads from one Envoy Check request: the HTTP request's [headers] (lower-case names, as Envoy
 * sends them), its [path] and the per-route extAuth [context] the api-gateway chart sets (INV-T-3990), and the
 * [requestId] Envoy assigned.
 */
data class AuthzRequest(
    val headers: Map<String, String>,
    val path: String,
    val context: Map<String, String>,
    val requestId: String = "",
) {
    /** The correlation id of an error envelope: the W3C traceparent (STK-34), else Envoy's request id. */
    val correlationId: String get() = headers["traceparent"] ?: requestId

    companion object {
        /** The route class (RouteClass.wire), for example `machine-api`. */
        const val ROUTE_CLASS = "route-class"

        /** The route's contract path template, for example `/v1/ap/{adapterId}/notifications`. */
        const val PATH_TEMPLATE = "path-template"

        /** On a machine-api route, the one API-23 machine scope its callers hold (INV-Q-9038 A); missing is a fault. */
        const val REQUIRED_SCOPE = "required-scope"

        /** On a machine-api route, the one additional scope admitted, `certification` (INV-Q-9032 A). */
        const val ALSO_SCOPE = "also-scope"

        /** On a signed-link page route, the name of the path parameter that carries the link reference. */
        const val LINK_PARAMETER = "link-parameter"

        /** The AP notification route's adapter parameter (transport/openapi.yaml, API-27). */
        const val ADAPTER_PARAMETER = "adapterId"
    }
}
