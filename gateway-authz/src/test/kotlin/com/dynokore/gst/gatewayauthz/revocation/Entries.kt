package com.dynokore.gst.gatewayauthz.revocation

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Builders for revocation entries as access-svc returns them (expiresAt = revokedAt + 15 minutes, API-23). */
object Entries {
    private val lifetime: Duration = Duration.ofMinutes(15)

    fun principal(
        ref: String,
        revokedAt: Instant,
        version: Long = 1,
    ) = entry(RevocationKind.PRINCIPAL, version, revokedAt, principalRef = ref)

    fun subject(
        sub: String,
        revokedAt: Instant,
        version: Long = 1,
    ) = entry(RevocationKind.SUBJECT, version, revokedAt, subject = sub)

    fun token(
        jti: String,
        revokedAt: Instant,
        version: Long = 1,
    ) = entry(RevocationKind.TOKEN, version, revokedAt, tokenId = jti)

    fun entry(
        kind: RevocationKind,
        version: Long,
        revokedAt: Instant,
        principalRef: String? = null,
        subject: String? = null,
        tokenId: String? = null,
    ) = RevocationEntry(
        UUID.randomUUID(),
        version,
        kind,
        principalRef,
        subject,
        tokenId,
        revokedAt,
        revokedAt.plus(lifetime),
    )
}
