package com.dynokore.gst.access.application.link

import com.dynokore.gst.access.domain.link.LinkRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** INV-AC-2765 at the use case boundary with fake ports. */
class SignedLinkInvalidationTest {
    private val kit = LinkKit()

    private fun wrongOf(passcode: String) = if (passcode == "000000") "000001" else "000000"

    @Test
    fun `INV-AC-2765 five wrong passcodes invalidate the link and the correct one is then refused`() {
        val (link, passcode) = kit.issued()
        val wrong = wrongOf(passcode)
        repeat(4) { assertTrue(kit.verify.handle(link.ref, wrong) is VerifyLinkOutcome.Wrong) }
        assertTrue(kit.alerts.isEmpty())
        assertTrue(kit.verify.handle(link.ref, wrong) is VerifyLinkOutcome.Invalidated)
        assertEquals(listOf("ACC-004" to "entity:E-A"), kit.alerts)
        assertEquals(VerifyLinkOutcome.Gone, kit.verify.handle(link.ref, passcode))
        assertEquals(1, kit.alerts.size)
    }

    @Test
    fun `INV-AC-2765 an event-issued link files ACC-004 under the envelope entity exactly once`() {
        val cmd = kit.command(consent = null, client = null, envelopeEntity = kit.envelope, eventId = "ev-9")
        val (link, passcode) = kit.issued(cmd)
        repeat(5) { kit.verify.handle(link.ref, wrongOf(passcode)) }
        kit.verify.handle(link.ref, passcode)
        assertEquals(listOf("ACC-004" to "entity:E-ENV"), kit.alerts)
    }

    @Test
    fun `INV-AC-2765 fewer than five may be set and a re-issued link has a new passcode`() {
        kit.setting = LinkPasscodeSetting(3, smsOnly = false)
        val (link, passcode) = kit.issued()
        repeat(3) { kit.verify.handle(link.ref, wrongOf(passcode)) }
        assertEquals(1, kit.alerts.size)
        val (second, secondPasscode) = kit.issued()
        assertTrue(second.ref != link.ref)
        assertTrue(kit.verify.handle(second.ref, secondPasscode) is VerifyLinkOutcome.Verified)
        assertEquals(VerifyLinkOutcome.Refused, kit.verify.handle(LinkRef("LNK-unknown"), passcode))
    }

    @Test
    fun `INV-AC-2765 a correct passcode within the limit opens the verified session`() {
        val (link, passcode) = kit.issued()
        kit.verify.handle(link.ref, wrongOf(passcode))
        assertEquals(VerifyLinkOutcome.Verified(link.ref, 1), kit.verify.handle(link.ref, passcode))
        assertTrue(kit.stored.getValue(link.ref.value).sessionOpen(kit.now))
    }
}
