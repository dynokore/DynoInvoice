package com.dynokore.gst.access.application.keys

import com.dynokore.gst.access.domain.revocation.RevocationEntry
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Where the last page ended: entries are ordered by version, then entryId (API-18 total order). */
data class EntryPosition(
    val version: Long,
    val entryId: UUID,
)

/** Read side of the revocation list. Entries are persisted by the operations that revoke (revokePrincipal and others). */
interface RevocationStore {
    /** The list version: the highest version ever assigned, 0 for an empty list. Dropped entries do not lower it. */
    fun currentVersion(): Long

    /**
     * Entries with `afterVersion < version <= throughVersion` that are live at [now] (`expiresAt` after [now]), ordered by
     * (version, entryId), strictly after [position] when given, at most [limit].
     */
    fun live(
        afterVersion: Long,
        throughVersion: Long,
        now: Instant,
        position: EntryPosition?,
        limit: Int,
    ): List<RevocationEntry>
}

/** The key the cursor integrity check uses (API-18: opaque, integrity-protected). */
fun interface CursorKey {
    fun bytes(): ByteArray
}

sealed interface RevocationListOutcome {
    data class Page(
        val listVersion: Long,
        val snapshot: Boolean,
        val items: List<RevocationEntry>,
        val nextCursor: String?,
    ) : RevocationListOutcome

    /** The cursor does not decode, fails its integrity check, or does not belong to this query: refused (HTTP 400). */
    data object MalformedCursor : RevocationListOutcome
}

/**
 * `getRevocationList` (INV-API-4001, INV-TS-4002): a consistent snapshot or a delta by version, expired entries dropped, a
 * cursor pinned to the list version of the first page and refused when malformed.
 *
 * Without a cursor and without `sinceVersion`, or with a `sinceVersion` above the current version (after a restore), the
 * response is a snapshot of all live entries; otherwise it is the live entries above `sinceVersion`. A later page is read
 * through the same pinned version, so entries added between pages never appear half way.
 */
class RevocationList(
    private val store: RevocationStore,
    private val cursorKey: CursorKey,
    private val clock: Clock,
) {
    fun handle(
        sinceVersion: Long?,
        limit: Int,
        cursor: String?,
    ): RevocationListOutcome {
        require(limit in 1..MAX_LIMIT) { "limit must be 1..$MAX_LIMIT" }
        require(sinceVersion == null || sinceVersion >= 0) { "sinceVersion must not be negative" }
        val now = clock.instant()
        val state =
            if (cursor == null) {
                val current = store.currentVersion()
                val snapshot = sinceVersion == null || sinceVersion > current
                CursorState(current, sinceVersion, snapshot, null)
            } else {
                decode(cursor)?.takeIf { it.since == sinceVersion } ?: return RevocationListOutcome.MalformedCursor
            }
        val after = if (state.snapshot) 0L else requireNotNull(state.since)
        val rows = store.live(after, state.pinned, now, state.position, limit + 1)
        val page = rows.take(limit)
        val next =
            if (rows.size > limit) {
                val last = page.last()
                encode(state.copy(position = EntryPosition(last.version, last.entryId)))
            } else {
                null
            }
        return RevocationListOutcome.Page(state.pinned, state.snapshot, page, next)
    }

    private data class CursorState(
        val pinned: Long,
        val since: Long?,
        val snapshot: Boolean,
        val position: EntryPosition?,
    )

    private fun encode(state: CursorState): String {
        val body =
            listOf(
                VERSION,
                state.pinned.toString(),
                state.since?.toString() ?: NONE,
                if (state.snapshot) "1" else "0",
                state.position?.version?.toString() ?: NONE,
                state.position?.entryId?.toString() ?: NONE,
            ).joinToString(SEPARATOR)
        return B64.encodeToString(body.toByteArray(Charsets.UTF_8)) + "." + B64.encodeToString(mac(body))
    }

    private fun decode(cursor: String): CursorState? {
        val parts = cursor.split('.')
        if (parts.size != 2) return null
        val body = runCatching { String(B64D.decode(parts[0]), Charsets.UTF_8) }.getOrNull() ?: return null
        val given = runCatching { B64D.decode(parts[1]) }.getOrNull() ?: return null
        if (!java.security.MessageDigest.isEqual(given, mac(body))) return null
        val fields = body.split(SEPARATOR)
        if (fields.size != FIELDS || fields[0] != VERSION) return null
        val pinned = fields[1].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val since = if (fields[2] == NONE) null else fields[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val snapshot =
            when (fields[3]) {
                "1" -> true
                "0" -> false
                else -> return null
            }
        val position =
            if (fields[4] == NONE && fields[5] == NONE) {
                null
            } else {
                val version = fields[4].toLongOrNull() ?: return null
                val id = runCatching { UUID.fromString(fields[5]) }.getOrNull() ?: return null
                EntryPosition(version, id)
            }
        return CursorState(pinned, since, snapshot, position)
    }

    private fun mac(body: String): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(cursorKey.bytes(), HMAC))
        return mac.doFinal(body.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val MAX_LIMIT = 500
        private const val HMAC = "HmacSHA256"
        private const val VERSION = "v1"
        private const val SEPARATOR = "|"
        private const val NONE = "-"
        private const val FIELDS = 6
        private val B64 = Base64.getUrlEncoder().withoutPadding()
        private val B64D = Base64.getUrlDecoder()
    }
}
