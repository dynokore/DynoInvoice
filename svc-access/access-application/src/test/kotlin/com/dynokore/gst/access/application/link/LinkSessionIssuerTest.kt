package com.dynokore.gst.access.application.link

import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.link.LinkRecipient
import com.dynokore.gst.access.domain.link.LinkRef
import com.dynokore.gst.access.domain.link.LinkSource
import com.dynokore.gst.access.domain.link.LinkStatus
import com.dynokore.gst.access.domain.link.SignedLink
import com.dynokore.platform.tenancykernel.TenantId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

class LinkSessionIssuerTest {
    private val now = Instant.parse("2026-11-07T02:00:00Z")
    private val tenant = TenantId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))
    private val ref = LinkRef("LNK-abcdefghijklmnopqrstuv")
    private val signed = mutableListOf<LinkSessionClaims>()
    private val issuer =
        LinkSessionIssuer(
            tenants = { if (it == ref) tenant else null },
            signer =
                LinkSessionSigner {
                    signed += it
                    "signed.${it.jti}"
                },
        )

    private fun link(
        expiresAt: Instant,
        status: LinkStatus = LinkStatus.ACTIVE,
    ) = SignedLink(
        ref = ref,
        purpose = "signatory",
        client = null,
        consent = null,
        alertEntity = EntityRef("E-A"),
        subject = null,
        recipient = LinkRecipient("s@example.test", null),
        expiresAt = expiresAt,
        passcodeDigest = "d",
        status = status,
        wrongEntries = 0,
        verifiedAt = now,
        invalidatedAt = null,
        source = LinkSource.EVENT,
        sourceEventId = "ev-1",
        version = 1,
    )

    private val verified = VerifyLinkOutcome.Verified(ref, 0)

    @Test
    @DisplayName("INV-AC-17809 verified link expiring in 3 days gives exp equal to now plus 15 minutes")
    fun longLink() {
        val s = issuer.sessionFor(link(now.plus(Duration.ofDays(3))), verified, now)
        assertNotNull(s)
        assertEquals(now.plus(Duration.ofMinutes(15)), s!!.claims.exp)
        assertEquals(s.claims.exp, s.expiresAt)
        assertEquals(now, s.claims.iat)
        assertEquals(tenant.toString(), s.claims.tenantId)
        assertEquals("signed.${s.claims.jti}", s.token)
        assertEquals(listOf(s.claims), signed)
    }

    @Test
    @DisplayName("INV-AC-17809 verified link expiring in 5 minutes gives exp equal to the link expiry")
    fun shortLink() {
        val expiry = now.plus(Duration.ofMinutes(5))
        assertEquals(expiry, issuer.sessionFor(link(expiry), verified, now)!!.claims.exp)
    }

    @Test
    @DisplayName("INV-AC-17809 link expiring in exactly 15 minutes gives exp of 15 minutes")
    fun boundaryLink() {
        val expiry = now.plus(Duration.ofMinutes(15))
        assertEquals(expiry, issuer.sessionFor(link(expiry), verified, now)!!.claims.exp)
    }

    @Test
    @DisplayName("INV-AC-17809 expiry equal to now gives null and no signer call")
    fun expiryAtNow() {
        assertNull(issuer.sessionFor(link(now), verified, now))
        assertEquals(0, signed.size)
    }

    @Test
    @DisplayName("INV-AC-17809 expiry before now gives null and no signer call")
    fun expiryBeforeNow() {
        assertNull(issuer.sessionFor(link(now.minusSeconds(1)), verified, now))
        assertEquals(0, signed.size)
    }

    @Test
    @DisplayName("INV-AC-17809 the four non-verified outcomes give null and zero signer calls")
    fun nonVerified() {
        val l = link(now.plus(Duration.ofDays(3)))
        listOf(
            VerifyLinkOutcome.Wrong(ref, 1),
            VerifyLinkOutcome.Invalidated(ref, 5),
            VerifyLinkOutcome.Gone,
            VerifyLinkOutcome.Refused,
        ).forEach { assertNull(issuer.sessionFor(l, it, now), it.toString()) }
        assertEquals(0, signed.size)
    }

    @Test
    @DisplayName("INV-AC-17809 a verified outcome of a revoked or invalidated stored link gives null")
    fun unusableLink() {
        listOf(LinkStatus.REVOKED, LinkStatus.INVALIDATED).forEach {
            assertNull(issuer.sessionFor(link(now.plus(Duration.ofDays(3)), it), verified, now))
        }
        assertEquals(0, signed.size)
    }

    @Test
    @DisplayName("INV-AC-17809 an outcome for another link gives null")
    fun otherLinkOutcome() {
        val other = VerifyLinkOutcome.Verified(LinkRef("LNK-zzzzzzzzzzzzzzzzzzzzzz"), 0)
        assertNull(issuer.sessionFor(link(now.plus(Duration.ofDays(3))), other, now))
        assertEquals(0, signed.size)
    }

    @Test
    @DisplayName("INV-AC-17809 a link with no tenant gives null")
    fun unknownTenant() {
        val noTenant = LinkSessionIssuer({ null }, { error("must not sign") })
        assertNull(noTenant.sessionFor(link(now.plus(Duration.ofDays(3))), verified, now))
    }

    @Test
    @DisplayName("INV-AC-17809 two calls give different jti of 128 bits")
    fun jtiDiffers() {
        val l = link(now.plus(Duration.ofDays(3)))
        val a = issuer.sessionFor(l, verified, now)!!.claims.jti
        val b = issuer.sessionFor(l, verified, now)!!.claims.jti
        assertNotEquals(a, b)
        assertEquals(16, Base64.getUrlDecoder().decode(a).size)
    }

    @Test
    @DisplayName("INV-AC-17809 sub equals link: plus the linkRef and not a look-alike")
    fun subject() {
        val sub = issuer.sessionFor(link(now.plus(Duration.ofDays(3))), verified, now)!!.claims.sub
        assertEquals("link:LNK-abcdefghijklmnopqrstuv", sub)
        assertNotEquals("Link:${ref.value}", sub)
        assertNotEquals(ref.value, sub)
        assertNotEquals("link:LNK-zzzzzzzzzzzzzzzzzzzzzz", sub)
    }
}
