package com.dynokore.gst.gatewayauthz.revocation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class RevocationListTest {
    private val revokedAt = Instant.parse("2026-10-12T08:00:00Z")
    private val now = revokedAt.plusSeconds(5)

    private fun identity(
        principal: String? = null,
        sub: String? = null,
        jti: String? = null,
        iat: Instant? = revokedAt.minusSeconds(60),
    ) = TokenIdentity(principal, sub, jti, iat)

    @Test
    fun `a PRINCIPAL entry refuses that principal's tokens issued at or before revokedAt, and not later ones`() {
        val list = RevocationList.EMPTY.replacedBy(1, listOf(Entries.principal("PRN-00088", revokedAt)))

        list.refuses(identity(principal = "PRN-00088"), now) shouldBe true
        list.refuses(identity(principal = "PRN-00088", iat = revokedAt), now) shouldBe true
        list.refuses(identity(principal = "PRN-00088", iat = revokedAt.plusSeconds(1)), now) shouldBe false
        list.refuses(identity(principal = "PRN-00089"), now) shouldBe false
    }

    @Test
    fun `a listed subject token issued after revokedAt is allowed, one issued before is refused`() {
        val list = RevocationList.EMPTY.replacedBy(1, listOf(Entries.subject("user-1", revokedAt)))

        list.refuses(identity(sub = "user-1", iat = revokedAt.minusSeconds(1)), now) shouldBe true
        list.refuses(identity(sub = "user-1", iat = revokedAt.plusSeconds(1)), now) shouldBe false
    }

    @Test
    fun `a TOKEN entry refuses that jti whatever its iat`() {
        val list = RevocationList.EMPTY.replacedBy(1, listOf(Entries.token("jti-1", revokedAt)))

        list.refuses(identity(jti = "jti-1", iat = revokedAt.plusSeconds(1)), now) shouldBe true
        list.refuses(identity(jti = "jti-2"), now) shouldBe false
    }

    @Test
    fun `a token without iat is refused by an entry for its subject`() {
        val list = RevocationList.EMPTY.replacedBy(1, listOf(Entries.subject("user-1", revokedAt)))

        list.refuses(identity(sub = "user-1", iat = null), now) shouldBe true
    }

    @Test
    fun `an entry of an unknown kind is kept but matches nothing (API-09)`() {
        val unknown = Entries.entry(RevocationKind.fromWire("DEVICE"), 1, revokedAt, "user-1")
        val list = RevocationList.EMPTY.replacedBy(1, listOf(unknown))

        unknown.kind shouldBe RevocationKind.UNKNOWN
        list.size shouldBe 1
        list.refuses(identity(sub = "user-1"), now) shouldBe false
    }

    @Test
    fun `an entry stops refusing at its expiresAt and is pruned`() {
        val list = RevocationList.EMPTY.replacedBy(1, listOf(Entries.token("jti-1", revokedAt)))
        val expiry = revokedAt.plus(Duration.ofMinutes(15))

        list.refuses(identity(jti = "jti-1"), expiry.minusMillis(1)) shouldBe true
        list.refuses(identity(jti = "jti-1"), expiry) shouldBe false
        list.withoutExpiredAt(expiry).size shouldBe 0
    }

    @Test
    fun `a delta adds to the list and a snapshot replaces it`() {
        val first = RevocationList.EMPTY.replacedBy(1, listOf(Entries.token("jti-1", revokedAt)))
        val delta = first.plus(2, listOf(Entries.token("jti-2", revokedAt, version = 2)))

        delta.listVersion shouldBe 2
        delta.refuses(identity(jti = "jti-1"), now) shouldBe true
        delta.refuses(identity(jti = "jti-2"), now) shouldBe true

        val snapshot = delta.replacedBy(3, listOf(Entries.token("jti-3", revokedAt, version = 3)))
        snapshot.refuses(identity(jti = "jti-1"), now) shouldBe false
        snapshot.refuses(identity(jti = "jti-3"), now) shouldBe true
    }
}
