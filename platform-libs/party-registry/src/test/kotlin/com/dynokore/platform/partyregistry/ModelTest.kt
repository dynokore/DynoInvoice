package com.dynokore.platform.partyregistry

import com.dynokore.platform.partyregistry.Fixtures.at
import com.dynokore.platform.partyregistry.Fixtures.evidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class ModelTest {
    @ParameterizedTest
    @EnumSource(ProvidedBy::class, names = ["MIGRATED"], mode = EnumSource.Mode.EXCLUDE)
    fun `a caller declares every provenance but MIGRATED`(by: ProvidedBy) {
        assertEquals(by, Provenance.declared(by, evidence, at).providedBy)
    }

    @Test
    fun `a caller can never declare MIGRATED, only the import path sets it`() {
        assertThrows(IllegalArgumentException::class.java) { Provenance.declared(ProvidedBy.MIGRATED, evidence, at) }
        assertEquals(ProvidedBy.MIGRATED, Provenance.migrated(evidence, at).providedBy)
    }

    @Test
    fun `an evidence reference cannot be blank, so an identifier cannot be built without one`() {
        assertThrows(IllegalArgumentException::class.java) { EvidenceRef(" ") }
        assertThrows(IllegalArgumentException::class.java) { EvidenceRef("") }
    }

    @Test
    fun `an identifier needs a value and carries its provenance`() {
        assertThrows(IllegalArgumentException::class.java) {
            Identifier(IdentifierScheme.UEN, "", Fixtures.declared())
        }
        val id = Fixtures.uen()
        assertEquals(ProvidedBy.COUNTERPARTY, id.provenance.providedBy)
        assertEquals(at, id.provenance.capturedAt)
        assertEquals(evidence, id.provenance.evidenceRef)
    }

    @Test
    fun `an address needs line 1 and a postal code`() {
        assertThrows(IllegalArgumentException::class.java) { Address("", null, "049145", null) }
        assertThrows(IllegalArgumentException::class.java) { Address("1 Raffles Place", null, " ", null) }
        assertThrows(IllegalArgumentException::class.java) { Address("1 Raffles Place", " ", "049145", null) }
        Address("1 Raffles Place", null, "049145", "SG")
    }

    @Test
    fun `a legal entity holds its identifiers and address`() {
        val entity =
            LegalEntity(
                LegalEntityId("E1"),
                "Acme Pte Ltd",
                listOf(Fixtures.uen()),
                Address("1 A", null, "049145", "SG"),
            )
        assertEquals(1, entity.identifiers.size)
        assertThrows(IllegalArgumentException::class.java) {
            LegalEntity(LegalEntityId("E1"), " ", emptyList(), entity.address)
        }
    }

    @Test
    fun `the identity triple and the submitting identity are separate value types`() {
        val supplier = SupplierIdentity(SenderId("S"), SupplierId("S"), SupplierGstn("M1"))
        val submitting = SubmittingIdentity(ConnectionId("C"), PrincipalId("P"))
        assertTrue((supplier as Any) !is SubmittingIdentity)
        assertTrue((submitting as Any) !is SupplierIdentity)
        assertThrows(IllegalArgumentException::class.java) { SupplierGstn("") }
    }
}
