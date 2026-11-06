package com.dynokore.gst.access.application.link

import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.link.LinkSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.crypto.BadPaddingException

/** The issue use case with fake ports (INV-T-4940; INV-REQ-899, INV-REQ-2758, INV-REQ-2762). */
class IssueSignedLinkTest {
    private val kit = LinkKit()

    @Test
    fun `a call with a consent and client entity stores both and two messages`() {
        val (link, _) = kit.issued()
        assertEquals(ConsentRef("C-A"), link.consent)
        assertEquals(kit.clientA, link.client)
        assertEquals(kit.clientA, link.alertEntity)
        assertEquals(LinkSource.CALL, link.source)
        assertEquals(2, kit.messages.size)
        assertEquals(link.ref.value, kit.messages[0].linkReference)
        assertEquals(MessageChannel.EMAIL, kit.messages[0].channel)
        assertEquals("sig@example.com", kit.messages[0].recipient)
        assertNull(kit.messages[0].sealedPasscode)
        assertNull(kit.messages[1].linkReference)
        assertEquals(MessageChannel.SMS, kit.messages[1].channel)
        assertEquals("+6591234567", kit.messages[1].recipient)
    }

    @Test
    fun `the event path stores a link with neither consent nor client entity`() {
        val out =
            kit.issue.handle(
                kit.command(consent = null, client = null, envelopeEntity = kit.envelope, eventId = "ev-1"),
            )
        val link = kit.stored.getValue((out as IssueLinkOutcome.Issued).ref.value)
        assertNull(link.consent)
        assertNull(link.client)
        assertEquals(kit.envelope, link.alertEntity)
        assertEquals(LinkSource.EVENT, link.source)
    }

    @Test
    fun `a pair with only one of the two is refused and stores nothing`() {
        val onlyConsent = kit.issue.handle(kit.command(client = null, envelopeEntity = kit.envelope))
        val onlyClient = kit.issue.handle(kit.command(consent = null))
        assertEquals(IssueLinkOutcome.Refused(IssueRefusal.PAIR_INCOMPLETE), onlyConsent)
        assertEquals(IssueLinkOutcome.Refused(IssueRefusal.PAIR_INCOMPLETE), onlyClient)
        assertTrue(kit.stored.isEmpty() && kit.messages.isEmpty() && kit.audits.isEmpty())
    }

    @Test
    fun `linkRef matches the pattern for 1000 generated links and no two are equal`() {
        val secrets = SecureLinkSecrets()
        val refs = List(1000) { secrets.newRef().value }
        val pattern = Regex("^LNK-[A-Za-z0-9_-]{22}$")
        assertTrue(refs.all { pattern.matches(it) })
        assertEquals(1000, refs.toSet().size)
        assertTrue(List(1000) { secrets.newPasscode() }.all { Regex("^[0-9]{6}$").matches(it) })
    }

    @Test
    fun `a call with an origin event id stores it on the link and the audit entry and the actor is the system actor`() {
        val out =
            kit.issue.handle(
                kit.command(consent = null, client = null, envelopeEntity = kit.envelope, eventId = "ev-77"),
            )
        val link = kit.stored.getValue((out as IssueLinkOutcome.Issued).ref.value)
        assertEquals("ev-77", link.sourceEventId)
        val entry = kit.audits.single()
        assertEquals(kit.systemActor, entry.actor)
        assertEquals("originEvent:ev-77", entry.reason)
        assertEquals("entity:E-ENV", entry.scope)
        assertFalse(kit.messages.any { it.recipient == "gst.masterdata" })
        // the same event again stores nothing more
        val again =
            kit.issue.handle(
                kit.command(consent = null, client = null, envelopeEntity = kit.envelope, eventId = "ev-77"),
            )
        assertEquals(IssueLinkOutcome.Duplicate(link.ref), again)
        assertEquals(1, kit.stored.size)
        assertEquals(2, kit.messages.size)
    }

    @Test
    fun `the sealed passcode matches the pattern opens only with the right recipient label and leaks nowhere`() {
        val (link, passcode) = kit.issued()
        val sealed = kit.messages.last().sealedPasscode!!
        assertTrue(Regex("^v1\\.[0-9a-f]{16}\\.[A-Za-z0-9_-]{512}$").matches(sealed))
        assertEquals(kit.keyId, sealed.split(".")[1])
        assertTrue(Regex("^[0-9]{6}$").matches(passcode))
        assertEquals(link.passcodeDigest, kit.digests.digestOf(link.ref, passcode))
        assertThrows(BadPaddingException::class.java) { kit.open(sealed, "other@example.com") }
        val seen = kit.messages.toString() + kit.audits.toString() + kit.alerts.toString()
        assertFalse(seen.contains(passcode))
        assertFalse(seen.contains(link.passcodeDigest))
    }

    @Test
    fun `without a mobile number the passcode goes by a second e-mail to the same address`() {
        kit.issued(kit.command(mobile = null))
        assertEquals(MessageChannel.EMAIL, kit.messages[1].channel)
        assertEquals("sig@example.com", kit.messages[1].recipient)
    }

    @Test
    fun `SMS only is refused with the property absent or false and accepted when true`() {
        kit.setting = LinkPasscodeSetting(5, smsOnly = true)
        kit.smsBound = false
        assertEquals(
            IssueLinkOutcome.Refused(IssueRefusal.SMS_ONLY_GATEWAY_NOT_BOUND),
            kit.issue.handle(kit.command()),
        )
        assertTrue(kit.stored.isEmpty() && kit.messages.isEmpty())
        kit.smsBound = true
        assertTrue(kit.issue.handle(kit.command()) is IssueLinkOutcome.Issued)
        assertEquals(MessageChannel.SMS, kit.messages[1].channel)
    }

    @Test
    fun `SMS only with no mobile number issues no link no message and no e-mail passcode`() {
        kit.setting = LinkPasscodeSetting(5, smsOnly = true)
        kit.smsBound = true
        for (mobile in listOf(null, "", "  ")) {
            assertEquals(
                IssueLinkOutcome.Refused(IssueRefusal.SMS_ONLY_NO_MOBILE),
                kit.issue.handle(kit.command(mobile = mobile)),
            )
        }
        assertTrue(kit.stored.isEmpty() && kit.messages.isEmpty() && kit.audits.isEmpty())
        assertNotEquals(0, SealingKeyBitsGuard.MIN)
    }

    @Test
    fun `a delivery key under 3072 bits is refused`() {
        val small =
            java.security.KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
                .public
                as java.security.interfaces.RSAPublicKey
        assertThrows(IllegalArgumentException::class.java) { SealingKey(SealingKey.keyIdOf(small), small) }
    }

    private object SealingKeyBitsGuard {
        const val MIN = SealingKey.MIN_BITS
    }
}
