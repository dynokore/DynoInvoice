package com.dynokore.gst.masterdata.domain.counterparty

import com.dynokore.platform.partyregistry.EvidenceRef
import com.dynokore.platform.partyregistry.Identifier
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.partyregistry.Provenance
import com.dynokore.platform.partyregistry.ProvidedBy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** INV-AC-357, INV-AC-9601 (dry-run listing): the duplicate, target and rejection rules of INV-Q-17289. */
class ImportAnalysisTest {
    private fun row(
        ref: String?,
        uen: String?,
        name: String? = "N",
    ) = ImportRow(ref, uen, name, "1 Road", "123456")

    private fun heldOf(vararg entries: Pair<String, String>) =
        object : HeldCounterparties {
            private val byRef =
                entries.associate { (ref, uen) ->
                    ref to
                        CounterpartyVersion(
                            PartyRef(ref),
                            1,
                            java.time.Instant.EPOCH,
                            CounterpartyContent(
                                listOf(
                                    Identifier(
                                        IdentifierScheme.UEN,
                                        uen,
                                        Provenance.declared(
                                            ProvidedBy.INTERNAL,
                                            EvidenceRef("e"),
                                            java.time.Instant.EPOCH,
                                        ),
                                    ),
                                ),
                                "N",
                                PostalAddress("1 Road", null, "123456"),
                                null,
                                emptyList(),
                                ProvidedBy.INTERNAL,
                            ),
                        )
                }

            override fun byUen(uen: String) = byRef.values.firstOrNull { it.content.uenOrNull() == uen }

            override fun current(party: PartyRef) = byRef[party.value]
        }

    @Test
    fun `INV-AC-357 two rows with one unheld UEN are both listed and neither is written`() {
        val rows = listOf(row("A", "53012345X"), row("B", "53012346X"), row("C", "201912345A"), row("D", "201912345A"))
        val result = analyseImport(rows, heldOf())
        assertEquals(listOf(ImportDuplicate("201912345A", listOf(3, 4), null)), result.duplicates)
        assertEquals(listOf(1, 2), result.targets.map { it.row })
        assertEquals(2, result.validCount)
    }

    @Test
    fun `INV-AC-357 a row whose UEN another counterparty holds is a duplicate with the existing reference`() {
        val result = analyseImport(listOf(row("CTP-1", "53012345X")), heldOf("CTP-2" to "53012345X"))
        assertEquals(listOf(ImportDuplicate("53012345X", listOf(1), "CTP-2")), result.duplicates)
        assertEquals(0, result.validCount)
    }

    @Test
    fun `INV-AC-357 a row without a reference is written to the holder and an unheld one is MDM-010`() {
        val held = heldOf("CTP-2" to "53012345X")
        val result = analyseImport(listOf(row(null, "53012345X", "New name"), row(null, "53012346X")), held)
        assertEquals(listOf("CTP-2"), result.targets.map { it.party.value })
        assertEquals(1, result.updateCount)
        assertEquals(
            listOf(
                RejectedImportRow(2, "MDM-010", "no counterparty reference and no held UEN match", "/counterpartyRef"),
            ),
            result.rejected,
        )
    }

    @Test
    fun `INV-AC-357 absent and invalid UENs and an incomplete address are rejected with their codes`() {
        val rows =
            listOf(
                row("A", null),
                row("B", "5301234X"),
                ImportRow("C", "53012345X", "N", null, "123456"),
                row("D", "NA"),
            )
        val result = analyseImport(rows, heldOf())
        assertEquals(listOf("MDM-002", "MDM-003", "MDM-006"), result.rejected.map { it.code })
        assertEquals(listOf(4), result.targets.map { it.row })
    }
}
