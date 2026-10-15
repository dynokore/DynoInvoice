package com.dynokore.platform.partyregistry

import com.dynokore.platform.partyregistry.PartyField.NotApplicable
import com.dynokore.platform.partyregistry.PartyField.Value
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NaAndHookTest {
    private val v = Value("x")

    @Test
    fun `all four NA is accepted as a whole`() {
        assertEquals(
            NaVerdict.Accepted(true),
            checkNaPopulation(NotApplicable, NotApplicable, NotApplicable, NotApplicable),
        )
    }

    @Test
    fun `no NA is accepted`() {
        assertEquals(NaVerdict.Accepted(false), checkNaPopulation(v, v, v, v))
    }

    @Test
    fun `every partial NA population returns MDM-004`() {
        for (mask in 1..14) {
            val f = (0..3).map { if (mask shr it and 1 == 1) NotApplicable else v }
            val verdict = checkNaPopulation(f[0], f[1], f[2], f[3])
            assertTrue(verdict is NaVerdict.PartialNa, "mask $mask")
            assertEquals("MDM-004", (verdict as NaVerdict.PartialNa).errorCode)
        }
    }

    @Test
    fun `NA is exactly the literal and a value never equals it`() {
        assertEquals(NotApplicable, PartyField.of("NA"))
        assertEquals(Value("na"), PartyField.of("na"))
        assertEquals(Value("NA "), PartyField.of("NA "))
        assertThrows(IllegalArgumentException::class.java) { Value("NA") }
        assertThrows(IllegalArgumentException::class.java) { PartyField.of(" ") }
    }

    private fun draft(
        uen: PartyField,
        vararg ids: Identifier,
    ) = CounterpartyDraft(uen, v, v, v, ids.toList())

    @Test
    fun `the injected hook decides UEN format and a failure is MDM-003`() {
        val checks = PartyChecks(Fixtures.hook)
        assertEquals(emptyList<PartyFinding>(), checks.check(draft(v, Fixtures.uen("OK1"))))
        assertEquals("MDM-003", checks.check(draft(v, Fixtures.uen("zzz"))).single().errorCode)
    }

    @Test
    fun `GSTN and Peppol participant ID go through the same hook and Keel assigns them no code`() {
        val checks = PartyChecks(Fixtures.hook)
        val ids =
            listOf(IdentifierScheme.GSTN, IdentifierScheme.PEPPOL_PARTICIPANT_ID).map {
                Identifier(it, "bad", Fixtures.declared())
            }
        val findings = checks.check(draft(v, *ids.toTypedArray()))
        assertEquals(listOf("GSTN", "PEPPOL_PARTICIPANT_ID"), findings.map { it.field })
        assertTrue(findings.all { it.errorCode == null })
    }

    @Test
    fun `scheme NONE is not format checked and partial NA is reported with the hook findings`() {
        val none = Identifier(IdentifierScheme.NONE, "NA", Fixtures.declared())
        val checks = PartyChecks { _, _ -> error("the hook must not run for NONE") }
        assertEquals(
            emptyList<PartyFinding>(),
            checks.check(
                draft(
                    NotApplicable,
                    none,
                ).copy(name = NotApplicable, addressLine1 = NotApplicable, postalCode = NotApplicable),
            ),
        )
        val partial = checks.check(draft(NotApplicable, none))
        assertEquals("MDM-004", partial.single().errorCode)
    }
}
