package com.dynokore.gst.access.application.link

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** INV-Q-9222 A at the use case boundary: which links get a role record, and when it is removed. */
class ClientSignatoryGrantsTest {
    private val kit = LinkKit()

    private fun wrongOf(passcode: String) = if (passcode == "000000") "000001" else "000000"

    @Test
    fun `INV-AC-926 a link issued to the client signatory of an agent consent gives exactly one ACTIVE record`() {
        val (link, _) = kit.issued()
        assertEquals(listOf("ACTIVE ${link.ref.value} E-A ${kit.now}"), kit.grantEvents)
    }

    @Test
    fun `INV-AC-926 a counterparty link issued on the event gives no record`() {
        kit.issued(kit.command(consent = null, client = null, envelopeEntity = kit.envelope, eventId = "ev-1"))
        assertTrue(kit.grantEvents.isEmpty())
    }

    @Test
    fun `INV-AC-926 a link that expires gives no further record and the refused entry changes nothing`() {
        val (link, passcode) = kit.issued()
        kit.now = link.expiresAt
        assertEquals(VerifyLinkOutcome.Refused, kit.verify.handle(link.ref, passcode))
        assertEquals(1, kit.grantEvents.size)
        assertTrue(kit.grantEvents.single().startsWith("ACTIVE"))
    }

    @Test
    fun `INV-AC-2765 the fifth wrong passcode gives one REMOVED record of the same link and later entries none`() {
        val (link, passcode) = kit.issued()
        repeat(4) { kit.verify.handle(link.ref, wrongOf(passcode)) }
        assertEquals(1, kit.grantEvents.size)
        kit.verify.handle(link.ref, wrongOf(passcode))
        kit.verify.handle(link.ref, passcode)
        assertEquals(
            listOf("ACTIVE ${link.ref.value} E-A ${kit.now}", "REMOVED ${link.ref.value} E-A ${kit.now}"),
            kit.grantEvents,
        )
    }

    @Test
    fun `INV-AC-2765 the invalidation of a counterparty link gives no record`() {
        val (link, passcode) = kit.issued(kit.command(null, null, envelopeEntity = kit.envelope, eventId = "ev-2"))
        repeat(5) { kit.verify.handle(link.ref, wrongOf(passcode)) }
        assertTrue(kit.grantEvents.isEmpty())
    }
}
