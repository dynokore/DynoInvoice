package com.dynokore.gst.gatewayauthz.check

/**
 * The answer to one Check request. Allowed: the request goes upstream unchanged (the Authorization header too) except
 * that [headersToRemove] are removed, and [responseHeaders] are added to the client response. Denied: the client gets
 * [status] with [responseHeaders] and the error envelope [body] of [code].
 */
data class AuthzDecision(
    val allowed: Boolean,
    val status: Int,
    val code: String?,
    val headersToRemove: List<String>,
    val responseHeaders: Map<String, String>,
    val body: String,
)
