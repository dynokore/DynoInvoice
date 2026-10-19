package com.dynokore.gst.access.application

import com.dynokore.gst.access.application.keys.CursorKey
import com.dynokore.gst.access.application.keys.EntryPosition
import com.dynokore.gst.access.application.keys.RevocationList
import com.dynokore.gst.access.application.keys.RevocationListOutcome
import com.dynokore.gst.access.application.keys.RevocationStore
import com.dynokore.gst.access.application.keys.VerificationKeyStore
import com.dynokore.gst.access.application.keys.VerificationKeys
import com.dynokore.gst.access.domain.revocation.RevocationEntry
import com.dynokore.gst.access.domain.revocation.RevocationKind
import com.dynokore.gst.access.domain.revocation.VerificationKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * INV-API-4001, INV-TS-4002: a consistent snapshot, deltas by version, expired entries dropped, a malformed cursor
 * refused.
 */
class RevocationListTest {
    private val start = Instant.parse("2026-10-19T08:00:00Z")
    private var now = start
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant(): Instant = now
        }
    private val entries = mutableListOf<RevocationEntry>()
    private var head = 0L

    private fun after(
        entry: RevocationEntry,
        position: EntryPosition,
    ) = entry.version > position.version ||
        (entry.version == position.version && entry.entryId.toString() > position.entryId.toString())

    private val store =
        object : RevocationStore {
            override fun currentVersion() = head

            override fun live(
                afterVersion: Long,
                throughVersion: Long,
                now: Instant,
                position: EntryPosition?,
                limit: Int,
            ) = entries
                .filter { it.version in (afterVersion + 1)..throughVersion && it.isLiveAt(now) }
                .filter { position == null || after(it, position) }
                .sortedWith(compareBy({ it.version }, { it.entryId.toString() }))
                .take(limit)
        }

    private val list = RevocationList(store, CursorKey { "cursor-key-1".toByteArray() }, clock)

    private fun revoke(
        principal: String,
        at: Instant = now,
    ): RevocationEntry {
        head += 1
        return RevocationEntry(UUID.randomUUID(), head, RevocationKind.PRINCIPAL, principal, null, null, at).also {
            entries +=
                it
        }
    }

    private fun page(
        since: Long? = null,
        limit: Int = 100,
        cursor: String? = null,
    ) = assertInstanceOf(RevocationListOutcome.Page::class.java, list.handle(since, limit, cursor))

    @Test
    fun `without sinceVersion the response is a snapshot of every live entry`() {
        repeat(3) { revoke("erp-$it") }
        val page = page()
        assertTrue(page.snapshot)
        assertEquals(3, page.items.size)
        assertEquals(3, page.listVersion)
        assertNull(page.nextCursor)
    }

    @Test
    fun `with sinceVersion the response holds only newer entries and is not a snapshot`() {
        repeat(3) { revoke("erp-$it") }
        val page = page(since = 2)
        assertFalse(page.snapshot)
        assertEquals(listOf(3L), page.items.map { it.version })
    }

    @Test
    fun `an unchanged list answers no items and the same list version`() {
        repeat(2) { revoke("erp-$it") }
        val page = page(since = 2)
        assertTrue(page.items.isEmpty())
        assertEquals(2, page.listVersion)
        assertFalse(page.snapshot)
    }

    @Test
    fun `a sinceVersion above the current version answers a full snapshot`() {
        repeat(2) { revoke("erp-$it") }
        val page = page(since = 99)
        assertTrue(page.snapshot)
        assertEquals(2, page.items.size)
    }

    @Test
    fun `an entry is dropped at the instant it expires and the list version does not fall`() {
        revoke("erp-old")
        now = start.plus(RevocationEntry.ACCESS_TOKEN_LIFETIME).minusSeconds(1)
        revoke("erp-new", now)
        assertEquals(2, page().items.size)
        now = start.plus(RevocationEntry.ACCESS_TOKEN_LIFETIME)
        val page = page()
        assertEquals(listOf("erp-new"), page.items.map { it.principalRef })
        assertEquals(2, page.listVersion)
    }

    @Test
    fun `a multi-page response is pinned to the list version of its first page`() {
        repeat(3) { revoke("erp-$it") }
        val first = page(limit = 2)
        assertEquals(2, first.items.size)
        revoke("added-between-pages")
        val second = page(limit = 2, cursor = first.nextCursor)
        assertEquals(listOf(3L), second.items.map { it.version })
        assertEquals(first.listVersion, second.listVersion)
        assertNull(second.nextCursor)
    }

    @Test
    fun `a malformed, forged or foreign cursor is refused`() {
        repeat(3) { revoke("erp-$it") }
        val cursor = page(limit = 1).nextCursor!!
        listOf(
            "",
            "not-a-cursor",
            "a.b",
            cursor.dropLast(2) + "xx",
            cursor.substringBefore('.') + "." + cursor.substringAfter('.').reversed(),
            "$cursor.extra",
        ).forEach { bad -> assertEquals(RevocationListOutcome.MalformedCursor, list.handle(null, 1, bad), bad) }
        // a cursor signed with another key
        val foreign = RevocationList(store, CursorKey { "another-key".toByteArray() }, clock)
        assertEquals(RevocationListOutcome.MalformedCursor, foreign.handle(null, 1, cursor))
        // a cursor of one query does not serve another sinceVersion
        assertEquals(RevocationListOutcome.MalformedCursor, list.handle(1, 1, cursor))
    }

    @Test
    fun `verification keys drop the expired and the not yet valid and stay in kid order`() {
        val keys =
            listOf(
                VerificationKey("b", "RS256", "QQ==", start.minusSeconds(10), start.plusSeconds(10)),
                VerificationKey("a", "RS256", "QQ==", start.minusSeconds(100), start.minusSeconds(1)),
                VerificationKey("c", "RS256", "QQ==", start.plusSeconds(1), start.plusSeconds(100)),
                VerificationKey("0", "RS256", "QQ==", start.minusSeconds(5), start.plusSeconds(5)),
            )
        val served = VerificationKeys(VerificationKeyStore { keys }, clock).handle()
        assertEquals(listOf("0", "b"), served.map { it.kid })
    }

    @Test
    fun `an entry names exactly one of principal, subject and token`() {
        assertThrows(IllegalArgumentException::class.java) {
            RevocationEntry(UUID.randomUUID(), 1, RevocationKind.TOKEN, "p", null, "t", start)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RevocationEntry(UUID.randomUUID(), 1, RevocationKind.SUBJECT, "p", null, null, start)
        }
        assertEquals(Duration.ofMinutes(15), RevocationEntry.ACCESS_TOKEN_LIFETIME)
    }
}
