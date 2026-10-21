package com.dynokore.gst.gatewayauthz.revocation

/**
 * One page of a getRevocationList response (INV-API-4001 RevocationListResponse): [snapshot] true when the response
 * replaces the caller's list, [nextCursor] null on the last page.
 */
data class RevocationPage(
    val listVersion: Long,
    val snapshot: Boolean,
    val items: List<RevocationEntry>,
    val nextCursor: String?,
)
