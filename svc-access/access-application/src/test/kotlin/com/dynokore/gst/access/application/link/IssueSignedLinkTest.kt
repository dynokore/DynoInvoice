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
        // the sender is the system actor, never the event's source: no message and no audit field names it
        assertEquals(listOf("sig@example.com", "+6591234567"), kit.messages.map { it.recipient })
        assertFalse(kit.audits.any { it.actor == "gst.masterdata" || it.reason?.contains("gst.masterdata") == true })
        // the same event again stores nothing more
        val again =
            kit.issue.handle(
                kit.command(consent = null, client = null, envelopeEntity = kit.envelope, eventId = "ev-77"),
            )
        assertEquals(IssueLinkOutcome.Duplicate(link.ref), again)
        assertEquals(1, kit.stored.size)
        assertEquals(2, kit.messages.size)
        assertEquals(1, kit.audits.size)
    }

    @Test
    fun `a look-alike origin event id issues a new link`() {
        fun event(id: String) = kit.command(consent = null, client = null, envelopeEntity = kit.envelope, eventId = id)
        val first = kit.issue.handle(event("ev-77")) as IssueLinkOutcome.Issued
        val second = kit.issue.handle(event("ev-78")) as IssueLinkOutcome.Issued
        assertNotEquals(first.ref, second.ref)
        assertEquals(2, kit.stored.size)
        assertEquals(4, kit.messages.size)
        assertEquals(2, kit.audits.size)
    }

    @Test
    fun `the refusal catalogue is closed`() {
        assertEquals(
            listOf(
                "PAIR_INCOMPLETE",
                "NO_ALERT_ENTITY",
                "EXPIRY_NOT_IN_FUTURE",
                "RECIPIENT_OR_PURPOSE_MISSING",
                "SMS_ONLY_NO_MOBILE",
                "SMS_ONLY_GATEWAY_NOT_BOUND",
            ),
            IssueRefusal.values().map { it.name },
        )
    }

    private fun assertRefusedStoringNothing(
        reason: IssueRefusal,
        command: IssueLinkCommand,
    ) {
        assertEquals(IssueLinkOutcome.Refused(reason), kit.issue.handle(command))
        assertTrue(kit.stored.isEmpty() && kit.messages.isEmpty() && kit.audits.isEmpty())
    }

    @Test
    fun `NO_ALERT_ENTITY a link with neither a client entity nor an envelope entity is never issued`() {
        assertRefusedStoringNothing(
            IssueRefusal.NO_ALERT_ENTITY,
            kit.command(consent = null, client = null, envelopeEntity = null, eventId = "ev-1"),
        )
    }

    @Test
    fun `EXPIRY_NOT_IN_FUTURE an expiry equal to or before now is refused`() {
        assertRefusedStoringNothing(IssueRefusal.EXPIRY_NOT_IN_FUTURE, kit.command().copy(expiresAt = kit.now))
        assertRefusedStoringNothing(
            IssueRefusal.EXPIRY_NOT_IN_FUTURE,
            kit.command().copy(expiresAt = kit.now.minusSeconds(1)),
        )
        assertTrue(kit.issue.handle(kit.command().copy(expiresAt = kit.now.plusSeconds(1))) is IssueLinkOutcome.Issued)
    }

    @Test
    fun `RECIPIENT_OR_PURPOSE_MISSING a blank recipient e-mail or a blank purpose is refused`() {
        assertRefusedStoringNothing(IssueRefusal.RECIPIENT_OR_PURPOSE_MISSING, kit.command().copy(email = " "))
        assertRefusedStoringNothing(IssueRefusal.RECIPIENT_OR_PURPOSE_MISSING, kit.command().copy(purpose = ""))
    }

    /** The formatted message and the throwable text of one captured log event. */
    private fun textOf(record: java.util.logging.LogRecord): String {
        val message =
            java.util.logging
                .SimpleFormatter()
                .formatMessage(record)
        val trace = java.io.StringWriter()
        record.thrown?.printStackTrace(java.io.PrintWriter(trace))
        return message + trace
    }

    @Test
    fun `no log event of a complete issue run holds the passcode or its digest`() {
        val logger =
            java.util.logging.Logger
                .getLogger("")
        val captured = mutableListOf<String>()
        val handler =
            object : java.util.logging.Handler() {
                override fun publish(record: java.util.logging.LogRecord) {
                    captured += textOf(record)
                }

                override fun flush() = Unit

                override fun close() = Unit
            }
        logger.addHandler(handler)
        try {
            val (link, passcode) = kit.issued()
            kit.verify.handle(link.ref, passcode)
            java.util.logging.Logger
                .getLogger("probe")
                .info("run done")
            assertTrue(captured.isNotEmpty(), "the capture works: the probe line was seen")
            assertTrue(captured.none { it.contains(passcode) || it.contains(link.passcodeDigest) })
            // probe: a deliberate line holding the passcode is caught by the same predicate
            java.util.logging.Logger
                .getLogger("probe")
                .info("passcode $passcode")
            assertTrue(captured.any { it.contains(passcode) })
        } finally {
            logger.removeHandler(handler)
        }
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
}
