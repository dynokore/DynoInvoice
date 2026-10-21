package com.dynokore.gst.gatewayauthz.revocation

import java.time.Instant
import java.util.UUID

/**
 * One entry of access-svc's revocation list (INV-API-4001 RevocationEntry): the key it refuses is [principalRef] for
 * [RevocationKind.PRINCIPAL], [subject] (the token's `sub`) for [RevocationKind.SUBJECT] and [tokenId] (the token's
 * `jti`) for [RevocationKind.TOKEN]. [expiresAt] is [revokedAt] plus the longest access-token lifetime (API-23); the
 * gateway drops the entry at that instant, as access-svc stops returning it.
 */
data class RevocationEntry(
    val entryId: UUID,
    val version: Long,
    val kind: RevocationKind,
    val principalRef: String?,
    val subject: String?,
    val tokenId: String?,
    val revokedAt: Instant,
    val expiresAt: Instant,
) {
    /** The value this entry refuses, or null when the entry names none it can match (an unknown kind). */
    val key: String?
        get() =
            when (kind) {
                RevocationKind.PRINCIPAL -> principalRef
                RevocationKind.SUBJECT -> subject
                RevocationKind.TOKEN -> tokenId
                RevocationKind.UNKNOWN -> null
            }
}
