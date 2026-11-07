package com.dynokore.gst.consoleBff.application.session

import com.dynokore.gst.consoleBff.application.session.SessionResolution.Refused
import com.dynokore.gst.consoleBff.application.session.SessionResolution.Resolved
import com.dynokore.gst.consoleBff.domain.session.SessionId
import com.dynokore.gst.consoleBff.domain.session.SessionIdHash
import com.dynokore.gst.consoleBff.domain.session.SessionIdParse
import com.dynokore.gst.consoleBff.domain.session.SessionPolicy
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class SessionResolverTest {
    private val cookie = "B".repeat(43)
    private val hash = SessionIdHash.of((SessionId.parse(cookie) as SessionIdParse.Parsed).id)
    private val now = Instant.parse("2026-01-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val policy = SessionPolicy(Duration.ofSeconds(1800), Duration.ofSeconds(43200))

    private fun row(
        opened: Instant = now.minusSeconds(60),
        seen: Instant = now.minusSeconds(10),
    ) = SessionRow(hash, "P1", "T1", 3L, opened, seen)

    private class Store(
        var row: SessionRow?,
        val failFind: Boolean = false,
        val failTouch: Boolean = false,
        val failDelete: Boolean = false,
    ) : SessionStore {
        val touched = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        override fun find(hash: String): SessionRow? {
            check(!failFind) { "down" }
            return row?.takeIf { it.hash == hash }
        }

        override fun touch(
            hash: String,
            now: Instant,
        ) {
            check(!failTouch) { "down" }
            touched += hash
        }

        override fun delete(hash: String) {
            check(!failDelete) { "down" }
            deleted += hash
        }
    }

    private class Standings(
        var answer: Standing,
        val fail: Boolean = false,
    ) : PrincipalStanding {
        var calls = 0

        override fun of(
            tenantId: String,
            principalId: String,
            authorityVersionAtOpen: Long,
        ): Standing {
            calls++
            check(!fail) { "down" }
            return answer
        }
    }

    private fun resolver(
        s: Store,
        p: Standings,
    ) = SessionResolver(policy, s, p, clock)

    @Test
    fun `INV-AC-14455 null or blank cookie is NO_COOKIE`() {
        val r = resolver(Store(row()), Standings(Standing.ACTIVE))
        for (v in listOf(null, "", "   ")) r.resolve(v) shouldBe Refused(RefusalReason.NO_COOKIE)
    }

    @Test
    fun `INV-AC-14455 malformed cookie is MALFORMED and the store is not asked`() {
        val s = Store(row())
        resolver(s, Standings(Standing.ACTIVE)).resolve("A".repeat(42)) shouldBe Refused(RefusalReason.MALFORMED)
    }

    @Test
    fun `INV-AC-14455 no row is UNKNOWN_SESSION`() {
        resolver(Store(null), Standings(Standing.ACTIVE)).resolve(cookie) shouldBe
            Refused(RefusalReason.UNKNOWN_SESSION)
    }

    @Test
    fun `INV-AC-14455 idle and absolute expiry are EXPIRED and delete the row`() {
        for (r in listOf(
            row(seen = now.minusSeconds(1800)),
            row(opened = now.minusSeconds(43200), seen = now.minusSeconds(1)),
        )) {
            val s = Store(r)
            resolver(s, Standings(Standing.ACTIVE)).resolve(cookie) shouldBe Refused(RefusalReason.EXPIRED)
            s.deleted shouldBe listOf(hash)
            s.touched shouldBe emptyList()
        }
    }

    @Test
    fun `INV-AC-14455 clock skew is EXPIRED without delete`() {
        val s = Store(row(seen = now.plusNanos(1)))
        resolver(s, Standings(Standing.ACTIVE)).resolve(cookie) shouldBe Refused(RefusalReason.EXPIRED)
        s.deleted shouldBe emptyList()
    }

    @Test
    fun `INV-AC-14455 revoked principal is REVOKED and deletes the row`() {
        val s = Store(row())
        resolver(s, Standings(Standing.REVOKED)).resolve(cookie) shouldBe Refused(RefusalReason.REVOKED)
        s.deleted shouldBe listOf(hash)
        s.touched shouldBe emptyList()
    }

    @Test
    fun `INV-AC-14455 unknown standing is AUTHORITY_UNAVAILABLE with no delete`() {
        val s = Store(row())
        resolver(s, Standings(Standing.UNKNOWN)).resolve(cookie) shouldBe Refused(RefusalReason.AUTHORITY_UNAVAILABLE)
        s.deleted shouldBe emptyList()
        s.touched shouldBe emptyList()
    }

    @Test
    fun `INV-AC-14455 active session is touched once and resolved`() {
        val s = Store(row())
        resolver(s, Standings(Standing.ACTIVE)).resolve(cookie) shouldBe Resolved("P1", "T1")
        s.touched shouldBe listOf(hash)
    }

    @Test
    fun `INV-AC-14455 a failing store or standing never resolves`() {
        val stores =
            listOf(
                Store(row(), failFind = true) to Standings(Standing.ACTIVE),
                Store(row(), failTouch = true) to Standings(Standing.ACTIVE),
                Store(row(seen = now.minusSeconds(1800)), failDelete = true) to Standings(Standing.ACTIVE),
                Store(row(), failDelete = true) to Standings(Standing.REVOKED),
                Store(row()) to Standings(Standing.ACTIVE, fail = true),
            )
        for ((s, p) in stores) resolver(s, p).resolve(cookie) shouldBe Refused(RefusalReason.STORE_UNAVAILABLE)
    }

    @Test
    fun `INV-AC-14455 the standing is consulted live on every call`() {
        val p = Standings(Standing.ACTIVE)
        val r = resolver(Store(row()), p)
        r.resolve(cookie) shouldBe Resolved("P1", "T1")
        p.answer = Standing.REVOKED
        r.resolve(cookie) shouldBe Refused(RefusalReason.REVOKED)
        p.calls shouldBe 2
    }

    @Test
    fun `INV-AC-14455 refusals and rows never contain the cookie value`() {
        val r = resolver(Store(row()), Standings(Standing.REVOKED)).resolve(cookie)
        r.toString().contains(cookie) shouldBe false
        row().toString().contains(cookie) shouldBe false
    }
}
