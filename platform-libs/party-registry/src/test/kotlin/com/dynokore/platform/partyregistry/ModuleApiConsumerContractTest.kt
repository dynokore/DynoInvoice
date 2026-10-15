package com.dynokore.platform.partyregistry

import com.dynokore.platform.partyregistry.PartyField.NotApplicable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Consumer-driven contract test of the module API (the ModuleApiConsumerContractTest pattern): a stub written the way
 * `masterdata-svc` writes its counterparty master (INV-T-4943) — constructor-injected with only the hook port, building
 * identifiers through the factories and checking through [PartyChecks] — run against the real implementation.
 * `masterdata-svc` does not exist yet: the stub plays its role.
 */
class ModuleApiConsumerContractTest {
    private class MasterdataSvcStub(
        formats: SchemeValidator,
    ) {
        private val checks = PartyChecks(formats)

        fun captureFromSignedLink(
            value: String,
            evidence: String,
            at: Instant,
        ) = Identifier(
            IdentifierScheme.UEN,
            value,
            Provenance.declared(ProvidedBy.COUNTERPARTY, EvidenceRef(evidence), at),
        )

        fun importRow(
            value: String,
            evidence: String,
            at: Instant,
        ) = Identifier(IdentifierScheme.UEN, value, Provenance.migrated(EvidenceRef(evidence), at))

        fun validate(
            id: Identifier,
            name: String,
            line1: String,
            postal: String,
        ) = checks.check(
            CounterpartyDraft(
                PartyField.of(id.value),
                PartyField.of(name),
                PartyField.of(line1),
                PartyField.of(postal),
                listOf(id),
            ),
        )

        fun triple() = SupplierIdentity(SenderId("SND"), SupplierId("SUP"), SupplierGstn("M1"))
    }

    private val stub = MasterdataSvcStub(Fixtures.hook)

    @Test
    fun `a signed-link value is COUNTERPARTY and an import row is MIGRATED`() {
        assertEquals(
            ProvidedBy.COUNTERPARTY,
            stub.captureFromSignedLink("OK1", "LINK-9", Fixtures.at).provenance.providedBy,
        )
        assertEquals(ProvidedBy.MIGRATED, stub.importRow("OK1", "BATCH-3", Fixtures.at).provenance.providedBy)
    }

    @Test
    fun `an invalid UEN blocks with MDM-003 and a partial NA with MDM-004`() {
        assertEquals(
            "MDM-003",
            stub
                .validate(
                    stub.captureFromSignedLink("zzz", "L", Fixtures.at),
                    "Acme",
                    "1 A",
                    "049145",
                ).single()
                .errorCode,
        )
        val na = Identifier(IdentifierScheme.NONE, "NA", Fixtures.declared(ProvidedBy.INTERNAL))
        assertEquals("MDM-004", stub.validate(na, "Acme", "NA", "NA").single().errorCode)
        assertEquals(emptyList<PartyFinding>(), stub.validate(na, "NA", "NA", "NA"))
    }

    @Test
    fun `the service reads the triple back as the supplier identity`() {
        assertEquals("SUP", stub.triple().supplierId.value)
        assertEquals(NotApplicable, PartyField.of("NA"))
        assertThrows(IllegalArgumentException::class.java) { SenderId("") }
    }
}
