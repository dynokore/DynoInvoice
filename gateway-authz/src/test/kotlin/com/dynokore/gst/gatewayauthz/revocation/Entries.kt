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
    ) = entry(RevocationKind.PRINCIPAL, version, revokedAt, key = ref)

    fun subject(
        sub: String,
        revokedAt: Instant,
        version: Long = 1,
    ) = entry(RevocationKind.SUBJECT, version, revokedAt, key = sub)

    fun token(
        jti: String,
        revokedAt: Instant,
        version: Long = 1,
    ) = entry(RevocationKind.TOKEN, version, revokedAt, key = jti)

    /** [key] fills the field the [kind] names: principalRef for PRINCIPAL, tokenId for TOKEN, subject otherwise. */
    fun entry(
        kind: RevocationKind,
        version: Long,
        revokedAt: Instant,
        key: String,
    ) = RevocationEntry(
        UUID.randomUUID(),
        version,
        kind,
        key.takeIf { kind == RevocationKind.PRINCIPAL },
        key.takeIf { kind != RevocationKind.PRINCIPAL && kind != RevocationKind.TOKEN },
        key.takeIf { kind == RevocationKind.TOKEN },
        revokedAt,
        revokedAt.plus(lifetime),
    )
}
