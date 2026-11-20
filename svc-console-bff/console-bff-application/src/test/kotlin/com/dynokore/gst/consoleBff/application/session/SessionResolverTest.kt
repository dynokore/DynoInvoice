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
import java.time.LocalDate
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

        override fun attachOperatorSession(
            hash: String,
            operator: OperatorSession,
        ): Boolean = error("not used by the resolver")
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

    private class Policies(
        val fail: Boolean = false,
    ) : TenantSessionPolicy {
        val calls = mutableListOf<Pair<String, LocalDate>>()

        override fun of(
            tenantId: String,
            at: LocalDate,
        ): SessionPolicy {
            calls += tenantId to at
            check(!fail) { "down" }
            return if (tenantId == "A") {
                SessionPolicy(Duration.ofMinutes(10), Duration.ofHours(2))
            } else {
                SessionPolicy(Duration.ofMinutes(30), Duration.ofHours(12))
            }
        }
    }

    private fun tenantRow(
        tenant: String,
        idleMinutes: Long,
        openedHoursAgo: Long = 1,
    ) = SessionRow(
        hash,
        "P1",
        tenant,
        3L,
        now.minus(Duration.ofHours(openedHoursAgo)),
        now.minus(Duration.ofMinutes(idleMinutes)),
    )

    private fun perTenant(
        s: Store,
        policies: Policies,
        c: Clock = clock,
    ) = SessionResolver(policies, s, Standings(Standing.ACTIVE), c)

    @Test
    fun `INV-AC-25109 tenant A is resolved at 9 minutes idle and expired at 11 with its row deleted`() {
        val ok = Store(tenantRow("A", 9))
        perTenant(ok, Policies()).resolve(cookie) shouldBe Resolved("P1", "A")
        ok.deleted shouldBe emptyList()
        val late = Store(tenantRow("A", 11))
        perTenant(late, Policies()).resolve(cookie) shouldBe Refused(RefusalReason.EXPIRED)
        late.deleted shouldBe listOf(hash)
        late.touched shouldBe emptyList()
    }

    @Test
    fun `INV-AC-25109 tenant A is expired 2 hours after opening with its row deleted`() {
        val s = Store(tenantRow("A", 1, openedHoursAgo = 2))
        perTenant(s, Policies()).resolve(cookie) shouldBe Refused(RefusalReason.EXPIRED)
        s.deleted shouldBe listOf(hash)
    }

    @Test
    fun `INV-AC-25109 tenant B is resolved at 29 minutes idle and expired at 31`() {
        perTenant(Store(tenantRow("B", 29)), Policies()).resolve(cookie) shouldBe Resolved("P1", "B")
        val late = Store(tenantRow("B", 31))
        perTenant(late, Policies()).resolve(cookie) shouldBe Refused(RefusalReason.EXPIRED)
        late.deleted shouldBe listOf(hash)
    }

    @Test
    fun `INV-AC-25109 the port gets the tenant of the row and the UTC date of the clock instant`() {
        val offsetClock = Clock.fixed(Instant.parse("2026-01-01T23:30:00Z"), ZoneOffset.ofHours(8))
        val p = Policies()
        perTenant(Store(tenantRow("A", 1)), p, offsetClock).resolve(cookie)
        perTenant(Store(tenantRow("B", 1)), p, offsetClock).resolve(cookie)
        p.calls shouldBe listOf("A" to LocalDate.of(2026, 1, 1), "B" to LocalDate.of(2026, 1, 1))
    }

    @Test
    fun `INV-AC-25109 a policy of one request is not reused for the next`() {
        val p = Policies()
        val s = Store(tenantRow("A", 1))
        val r = perTenant(s, p)
        r.resolve(cookie)
        s.row = tenantRow("B", 20)
        r.resolve(cookie) shouldBe Resolved("P1", "B")
        p.calls.map { it.first } shouldBe listOf("A", "B")
    }

    @Test
    fun `INV-AC-25109 a throwing port is STORE_UNAVAILABLE and touches nothing`() {
        val s = Store(tenantRow("A", 1))
        perTenant(s, Policies(fail = true)).resolve(cookie) shouldBe Refused(RefusalReason.STORE_UNAVAILABLE)
        s.touched shouldBe emptyList()
        s.deleted shouldBe emptyList()
    }
}
