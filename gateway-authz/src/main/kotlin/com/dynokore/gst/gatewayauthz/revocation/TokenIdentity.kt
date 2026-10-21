package com.dynokore.gst.gatewayauthz.revocation

import java.time.Instant

/**
 * What the revocation list is matched against for one verified token: its service principal (machine tokens), its
 * `sub`, its `jti` and its `iat`. A token without `iat` cannot be shown to postdate a revocation, so a PRINCIPAL or
 * SUBJECT entry for it refuses it.
 */
data class TokenIdentity(
    val principalRef: String?,
    val subject: String?,
    val tokenId: String?,
    val issuedAt: Instant?,
)
