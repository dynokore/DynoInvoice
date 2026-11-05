package com.dynokore.gst.masterdata.domain.registry

import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyContent
import com.dynokore.platform.partyregistry.EvidenceRef
import com.dynokore.platform.partyregistry.Identifier
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.partyregistry.Provenance
import com.dynokore.platform.partyregistry.ProvidedBy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class RegistryDomainTest {
    private val at = Instant.parse("2026-10-24T01:00:00Z")
    private val day = CacheLifetimes.DEFAULT

    @Test
    fun `an answer is served strictly before its lifetime ends and a zero lifetime is never served`() {
        assertTrue(day.serves(true, at, at.plus(Duration.ofHours(23).plusMinutes(59))))
        assertFalse(day.serves(true, at, at.plus(Duration.ofHours(24))))
        assertFalse(day.serves(true, at, at.plus(Duration.ofHours(24).plusMinutes(1))))
        assertTrue(day.serves(false, at, at.plus(Duration.ofMinutes(59))))
        assertFalse(day.serves(false, at, at.plus(Duration.ofMinutes(61))))
        assertFalse(CacheLifetimes(Duration.ZERO, Duration.ZERO).serves(true, at, at))
        assertThrows(IllegalArgumentException::class.java) { CacheLifetimes(Duration.ofMinutes(-1), Duration.ZERO) }
    }

    private val provenance = Provenance.declared(ProvidedBy.INTERNAL, EvidenceRef("E"), at)

    private fun content(
        name: String?,
        gstn: String? = null,
    ) = CounterpartyContent(
        identifiers =
            listOfNotNull(
                Identifier(IdentifierScheme.UEN, "201812345K", provenance),
                gstn?.let { Identifier(IdentifierScheme.GSTN, it, provenance) },
            ),
        legalName = name,
        address = null,
        arcs = null,
        schemes = emptyList(),
        providedBy = ProvidedBy.INTERNAL,
    )

    private fun fact(
        name: String? = "ACME PTE. LTD.",
        gstn: String? = "M2-1234567-0",
        found: Boolean = true,
    ) = RegisterFact(found, if (found) "REGISTERED" else "NOT_REGISTERED", gstn, name, null)

    @Test
    fun `a differing name is suggested, applied or ignored by the policy`() {
        val held = content("Acme Pte Ltd")
        val suggest = Reconciliation.Suggest("ACME PTE. LTD.", "Acme Pte Ltd")
        val apply = Reconciliation.Apply("ACME PTE. LTD.", "Acme Pte Ltd")
        assertEquals(suggest, reconcile(held, fact(), RegisterPolicy.SUGGEST_CONFIRM))
        assertEquals(apply, reconcile(held, fact(), RegisterPolicy.AUTO_APPLY))
        assertEquals(Reconciliation.NoChange, reconcile(held, fact(), RegisterPolicy.OFF))
    }

    @Test
    fun `an equal name, no register name or a UEN the register does not hold changes nothing`() {
        val held = content("ACME PTE. LTD.")
        RegisterPolicy.entries.forEach {
            assertEquals(Reconciliation.NoChange, reconcile(held, fact(), it))
            assertEquals(Reconciliation.NoChange, reconcile(content("Acme"), fact(name = null), it))
            assertEquals(Reconciliation.NoChange, reconcile(content("Acme"), fact(name = "  "), it))
            assertEquals(Reconciliation.NoChange, reconcile(content("Acme"), fact(found = false), it))
        }
    }

    @Test
    fun `a GSTN that differs from the register is a conflict under every policy and an equal one is not`() {
        RegisterPolicy.entries.forEach {
            assertEquals(
                Reconciliation.Conflict("M2-9999999-9", "M2-1234567-0"),
                reconcile(content("Acme", gstn = "M2-9999999-9"), fact(), it),
            )
            assertEquals(
                Reconciliation.NoChange,
                reconcile(content("ACME PTE. LTD.", gstn = "M2-1234567-0"), fact(), it),
            )
        }
        assertEquals(
            Reconciliation.Suggest("ACME PTE. LTD.", "Acme"),
            reconcile(content("Acme", gstn = "M2-1234567-0"), fact(), RegisterPolicy.SUGGEST_CONFIRM),
        )
    }

    @Test
    fun `the evidence bytes quote what they hold and carry the queried value, the response and the time`() {
        val e = LookupEvidence(RegistryKind.DIRECTORY, "0195:A\"B", "{\"x\":\"\\n\"}", at)
        val expected =
            "{\"kind\":\"DIRECTORY\",\"queried\":\"0195:A\\\"B\"," +
                "\"response\":\"{\\\"x\\\":\\\"\\\\n\\\"}\",\"at\":\"2026-10-24T01:00:00Z\"}"
        assertEquals(expected, e.bytes)
    }

    @Test
    fun `a network-registration item is confirmed with a time, and holds an AP reference only when confirmed`() {
        assertEquals(RegistrationState.OUTSTANDING, NetworkRegistration.outstanding("E").state)
        assertThrows(IllegalArgumentException::class.java) {
            NetworkRegistration("E", RegistrationState.CONFIRMED, "AP", null, at)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkRegistration("E", RegistrationState.OUTSTANDING, "AP", null, at)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NetworkRegistration("E", RegistrationState.OUTSTANDING, null, at, at)
        }
    }

    @Test
    fun `the register GSTN is offered only for a REGISTERED UEN whose counterparty holds none`() {
        assertEquals("M2-1234567-0", registerGstnToAdd(content("Acme"), fact()))
        assertEquals("M2-1234567-0", registerGstnToAdd(content("Acme"), fact(gstn = " M2-1234567-0 ")))
        // held: the same one needs nothing, a different one is the MDM-007 conflict of reconcile
        assertNull(registerGstnToAdd(content("Acme", gstn = "M2-1234567-0"), fact()))
        assertNull(registerGstnToAdd(content("Acme", gstn = "M2-7654321-0"), fact()))
        assertNull(registerGstnToAdd(content("Acme"), fact(gstn = null)))
        assertNull(registerGstnToAdd(content("Acme"), fact(gstn = "  ")))
        assertNull(registerGstnToAdd(content("Acme"), fact(found = false)))
        assertNull(registerGstnToAdd(content("Acme"), fact().copy(status = "DEREGISTERED")))
    }
}
