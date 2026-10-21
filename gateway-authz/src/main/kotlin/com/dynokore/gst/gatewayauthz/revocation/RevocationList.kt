package com.dynokore.gst.gatewayauthz.revocation

import java.time.Instant
import java.util.UUID

/**
 * The gateway's in-memory revocation list (coordinator ruling INV-Q-3973 A, INV-API-4001), immutable: a refresh
 * builds a new one. A PRINCIPAL or SUBJECT entry refuses a token of that principal or subject issued at or before its
 * revokedAt (iat <= revokedAt), and a TOKEN entry refuses that jti (coordinator ruling INV-Q-4008 A). An entry stops
 * refusing at its expiresAt; deltas never carry removals.
 */
class RevocationList private constructor(
    val listVersion: Long,
    private val entries: Map<UUID, RevocationEntry>,
) {
    private val byKind: Map<RevocationKind, Map<String, List<RevocationEntry>>> =
        entries.values
            .filter { it.key != null }
            .groupBy { it.kind }
            .mapValues { (_, list) -> list.groupBy { requireNotNull(it.key) } }

    val size: Int get() = entries.size

    /** A full snapshot replaces this list (snapshot true, INV-API-4001). */
    fun replacedBy(
        listVersion: Long,
        items: List<RevocationEntry>,
    ): RevocationList = RevocationList(listVersion, items.associateBy { it.entryId })

    /** A delta (snapshot false) adds the entries above the previous version. */
    fun plus(
        listVersion: Long,
        items: List<RevocationEntry>,
    ): RevocationList = RevocationList(listVersion, entries + items.associateBy { it.entryId })

    /** The list without the entries whose expiresAt has passed at [now]. */
    fun withoutExpiredAt(now: Instant): RevocationList {
        val live = entries.filterValues { it.expiresAt.isAfter(now) }
        return if (live.size == entries.size) this else RevocationList(listVersion, live)
    }

    /** True when a live entry refuses [token] at [now]. */
    fun refuses(
        token: TokenIdentity,
        now: Instant,
    ): Boolean = listedByJti(token, now) ||
        listedIssuedBefore(RevocationKind.PRINCIPAL, token.principalRef, token.issuedAt, now) ||
        listedIssuedBefore(RevocationKind.SUBJECT, token.subject, token.issuedAt, now)

    private fun listedByJti(
        token: TokenIdentity,
        now: Instant,
    ): Boolean = live(RevocationKind.TOKEN, token.tokenId, now).isNotEmpty()

    private fun listedIssuedBefore(
        kind: RevocationKind,
        key: String?,
        issuedAt: Instant?,
        now: Instant,
    ): Boolean = live(kind, key, now).any { issuedAt == null || !issuedAt.isAfter(it.revokedAt) }

    private fun live(
        kind: RevocationKind,
        key: String?,
        now: Instant,
    ): List<RevocationEntry> {
        if (key == null) return emptyList()
        return byKind[kind]?.get(key).orEmpty().filter { it.expiresAt.isAfter(now) }
    }

    companion object {
        /** No list loaded yet: the gateway is not ready (coordinator ruling INV-Q-4008 A). */
        val EMPTY = RevocationList(0, emptyMap())
    }
}
