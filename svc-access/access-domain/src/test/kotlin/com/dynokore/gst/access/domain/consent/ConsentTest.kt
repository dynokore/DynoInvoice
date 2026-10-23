package com.dynokore.gst.access.domain.consent

import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The consent record and the agent policy decide on the record alone (INV-REQ-912, INV-REQ-913, INV-REQ-865). */
class ConsentTest {
    private val day = LocalDate.of(2026, 10, 1)
    private val client = EntityRef("CLI-A")
    private val agent = IdentityRef("AGT-1")

    private fun consent(
        vararg items: ScopeItem,
        status: ConsentStatus = ConsentStatus.LIVE,
        validFrom: LocalDate = day,
    ) = Consent(
        ConsentRef("CNS-1"),
        client,
        agent,
        ConsentTerms(items.toSet(), validFrom, "signatory@client.example"),
        status,
        if (status == ConsentStatus.WITHDRAWN) "HOS-1" else null,
        0,
    )

    @Test
    fun `a consent grants exactly the items it lists and no item implies another`() {
        val consent = consent(ScopeItem.SUBMIT_INVOICE_DATA)
        assertTrue(consent.allows(ScopeItem.SUBMIT_INVOICE_DATA, day))
        assertFalse(consent.allows(ScopeItem.FILE_GST_RETURN, day))
        assertFalse(consent.allows(ScopeItem.MAPPING_APPROVAL, day))
        ScopeItem.entries.forEach { assertFalse(consent().allows(it, day), "$it is not granted by default") }
    }

    @Test
    fun `a consent is not live before its start day and a withdrawn consent is never live`() {
        assertFalse(consent(ScopeItem.SUBMIT_INVOICE_DATA, validFrom = day.plusDays(1)).isLiveOn(day))
        assertTrue(consent(ScopeItem.SUBMIT_INVOICE_DATA, validFrom = day).isLiveOn(day))
        val withdrawn = consent(ScopeItem.SUBMIT_INVOICE_DATA).withdrawn("HOS-2")
        assertEquals(ConsentStatus.WITHDRAWN, withdrawn.status)
        assertFalse(withdrawn.isLiveOn(day.plusYears(1)))
        assertFalse(withdrawn.allows(ScopeItem.SUBMIT_INVOICE_DATA, day))
    }

    @Test
    fun `a withdrawal is idempotent and keeps the first statement`() {
        val withdrawn = consent(ScopeItem.SUBMIT_INVOICE_DATA).withdrawn("HOS-2")
        assertSame(withdrawn, withdrawn.withdrawn("HOS-3"))
        assertEquals("HOS-2", withdrawn.handoverStatementRef)
    }

    @Test
    fun `a withdrawn consent without a statement and a live one with a statement cannot be built`() {
        assertThrows(IllegalArgumentException::class.java) { consent().copy(status = ConsentStatus.WITHDRAWN) }
        assertThrows(IllegalArgumentException::class.java) { consent().copy(handoverStatementRef = "HOS-1") }
    }

    @Test
    fun `the agent policy refuses no record, another agent, another client, a missing item and a withdrawn record`() {
        val live = consent(ScopeItem.SUBMIT_INVOICE_DATA)
        val item = ScopeItem.SUBMIT_INVOICE_DATA

        fun permits(
            record: Consent?,
            who: IdentityRef = agent,
            where: EntityRef = client,
            what: ScopeItem = item,
        ) = AgentConsentPolicy.permits(record, who, where, what, day)
        assertTrue(permits(live))
        assertFalse(permits(null))
        assertFalse(permits(live, who = IdentityRef("AGT-2")))
        assertFalse(permits(live, who = IdentityRef("agt-1")))
        assertFalse(permits(live, where = EntityRef("CLI-B")))
        assertFalse(permits(live, what = ScopeItem.FILE_GST_RETURN))
        assertFalse(permits(live.withdrawn("HOS-2")))
    }

    @Test
    fun `the client policy ignores the consent agent but keeps client, item, validity and withdrawal`() {
        val live = consent(ScopeItem.SUBMIT_INVOICE_DATA)
        val item = ScopeItem.SUBMIT_INVOICE_DATA
        assertTrue(AgentConsentPolicy.permitsClient(live, client, item, day))
        assertFalse(AgentConsentPolicy.permitsClient(null, client, item, day))
        assertFalse(AgentConsentPolicy.permitsClient(live, EntityRef("CLI-B"), item, day))
        assertFalse(AgentConsentPolicy.permitsClient(live, client, ScopeItem.MAPPING_APPROVAL, day))
        assertFalse(AgentConsentPolicy.permitsClient(live.withdrawn("HOS-2"), client, item, day))
    }

    @Test
    fun `the three scope items are the VP-10 keys`() {
        assertEquals(
            listOf("SUBMIT_INVOICE_DATA", "FILE_GST_RETURN", "MAPPING_APPROVAL"),
            ScopeItem.entries.map { it.wire },
        )
    }
}
