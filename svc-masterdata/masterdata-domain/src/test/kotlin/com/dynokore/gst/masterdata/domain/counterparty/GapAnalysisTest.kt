package com.dynokore.gst.masterdata.domain.counterparty

import com.dynokore.platform.partyregistry.IdentifierScheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/** INV-REQ-316 classes 1, 2, 4, 5 and the class 3 comparison, without a framework (INV-AC-1608 domain part). */
class GapAnalysisTest {
    private val now = Instant.parse("2026-11-06T01:00:00Z")
    private val full = PostalAddress("1 Raffles Place", null, "048616")

    private fun party(
        ref: String,
        vararg identifiers: Pair<IdentifierScheme, String>,
        address: PostalAddress? = full,
    ) = CounterpartyVersion(
        PartyRef(ref),
        1,
        now,
        null.after(
            CounterpartyChange(identifiers.map { IdentifierInput(it.first, it.second) }, "Acme", address, null, null),
            "EV",
            WriteOrigin.OPERATOR,
            now,
        ),
    )

    private fun rows(
        vararg parties: CounterpartyVersion,
        valid: (String) -> Boolean = { !it.startsWith("BAD") },
    ) = gapRows(parties.toList(), valid).map { it.party.value to it.gapClass }

    @Test
    fun `one counterparty of each class is listed once under its class and no other is listed`() {
        val found =
            rows(
                party("P-ABSENT"),
                party("P-INVALID", IdentifierScheme.UEN to "BAD-1"),
                party("P-ADDRESS", IdentifierScheme.UEN to "201954321B", address = PostalAddress("1 Road", null, null)),
                party("P-DUP1", IdentifierScheme.UEN to "200000001A"),
                party("P-DUP2", IdentifierScheme.UEN to "200000001a"),
                party("P-CLEAN", IdentifierScheme.UEN to "200000002A"),
                party("P-NA", IdentifierScheme.NONE to "NA", address = PostalAddress("NA", null, "NA")),
            )
        assertEquals(
            listOf(
                "P-ABSENT" to GapClass.UEN_ABSENT,
                "P-ADDRESS" to GapClass.ADDRESS_INCOMPLETE,
                "P-DUP1" to GapClass.DUPLICATE_UEN,
                "P-DUP2" to GapClass.DUPLICATE_UEN,
                "P-INVALID" to GapClass.UEN_INVALID,
            ),
            found,
        )
    }

    @Test
    fun `a missing address line 1 or postal code is ADDRESS_INCOMPLETE and a missing address is too`() {
        val found =
            rows(
                party("P-1", IdentifierScheme.UEN to "201954321B", address = PostalAddress(" ", null, "048616")),
                party("P-2", IdentifierScheme.UEN to "201954322B", address = null),
                party(
                    "P-3",
                    IdentifierScheme.UEN to "201954323B",
                    address = PostalAddress("1 Road", "Unit 2", "048616"),
                ),
            )
        assertEquals(listOf("P-1", "P-2"), found.map { it.first })
        assertTrue(found.all { it.second == GapClass.ADDRESS_INCOMPLETE })
    }

    @Test
    fun `the error codes are those of the report`() {
        assertEquals(
            listOf("MDM-002", "MDM-003", "MDM-007", "MDM-006", null),
            GapClass.entries.map { it.errorCode },
        )
    }

    @Test
    fun `class 3 needs a held GSTN that differs from the register's number or is not REGISTERED there`() {
        assertTrue(conflictsWithRegister("M1", true, "REGISTERED", "M2"))
        assertTrue(conflictsWithRegister("M1", true, "DEREGISTERED", "M1"))
        assertFalse(conflictsWithRegister("M1", true, "REGISTERED", "M1"))
        assertFalse(conflictsWithRegister(null, true, "REGISTERED", "M2"))
        assertFalse(conflictsWithRegister("M1", false, "NOT_FOUND", null))
    }

    @Test
    fun `with the UEN rule a 7-digit UEN is listed once as invalid and a valid one, NA and none are not`() {
        val found =
            rows(
                party("P-SHORT", IdentifierScheme.UEN to "5301234X"),
                party("P-OK", IdentifierScheme.UEN to "53012345X"),
                party("P-SPACE", IdentifierScheme.UEN to "53012345X "),
                party("P-NA", IdentifierScheme.NONE to "NA", address = PostalAddress("NA", null, "NA")),
                valid = UenFormat::accepts,
            )
        assertEquals(
            listOf("P-SHORT" to GapClass.UEN_INVALID, "P-SPACE" to GapClass.UEN_INVALID),
            found,
        )
    }
}
