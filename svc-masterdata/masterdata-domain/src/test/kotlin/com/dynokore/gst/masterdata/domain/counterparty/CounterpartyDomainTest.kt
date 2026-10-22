package com.dynokore.gst.masterdata.domain.counterparty

import com.dynokore.platform.partyregistry.EvidenceRef
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.partyregistry.Provenance
import com.dynokore.platform.partyregistry.ProvidedBy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

/** The rules of the counterparty master, without a framework: findings, merge, provenance, status dates, sameness. */
class CounterpartyDomainTest {
    private val now = Instant.parse("2026-10-22T01:00:00Z")

    private fun change(
        vararg identifiers: Pair<IdentifierScheme, String>,
        name: String? = null,
        address: PostalAddress? = null,
        arcs: Boolean? = null,
        schemes: List<SchemeInput>? = null,
    ) = CounterpartyChange(identifiers.map { IdentifierInput(it.first, it.second) }, name, address, arcs, schemes)

    private fun content(
        vararg identifiers: Pair<IdentifierScheme, String>,
        name: String? = null,
        address: PostalAddress? = null,
    ): CounterpartyContent =
        null.after(change(*identifiers, name = name, address = address), "EV", WriteOrigin.OPERATOR, now)

    private fun codes(content: CounterpartyContent) = content.findings().map { it.code }

    @Test
    fun `a scheme NONE counterparty with NA in all four fields has no finding`() {
        val c = content(IdentifierScheme.NONE to "NA", name = "NA", address = PostalAddress("NA", null, "NA"))
        assertEquals(emptyList<FindingCode>(), codes(c))
    }

    @Test
    fun `scheme NONE with NA in some but not all four fields is MDM-004, over every subset of the four`() {
        for (mask in 0 until 16) {
            val na = { bit: Int -> mask and (1 shl bit) != 0 }
            val uen = if (na(0)) "NA" else "U-1"
            val c =
                content(
                    IdentifierScheme.NONE to uen,
                    name = if (na(1)) "NA" else "Real",
                    address = PostalAddress(if (na(2)) "NA" else "1 Road", null, if (na(3)) "NA" else "048616"),
                )
            val expected = if (mask == 15) emptyList() else listOf(FindingCode.MDM_004)
            assertEquals(expected, codes(c), "mask $mask")
        }
    }

    @Test
    fun `a counterparty with a UEN carries NA nowhere, NA in any field is MDM-004, all four NA too`() {
        val ok =
            content(
                IdentifierScheme.UEN to "201954321B",
                name = "Acme",
                address = PostalAddress("1 Road", null, "048616"),
            )
        assertEquals(emptyList<FindingCode>(), codes(ok))
        for (mask in 1 until 16) {
            val na = { bit: Int -> mask and (1 shl bit) != 0 }
            val c =
                content(
                    IdentifierScheme.UEN to if (na(0)) "NA" else "201954321B",
                    name = if (na(1)) "NA" else "Acme",
                    address = PostalAddress(if (na(2)) "NA" else "1 Road", null, if (na(3)) "NA" else "048616"),
                )
            assertEquals(listOf(FindingCode.MDM_004), codes(c), "mask $mask")
        }
    }

    @Test
    fun `no UEN and a scheme other than NONE is MDM-002, an overseas registration number is a UEN`() {
        val address = PostalAddress("1 Road", null, "048616")
        assertEquals(
            listOf(FindingCode.MDM_002),
            codes(content(IdentifierScheme.GSTN to "G-1", name = "A", address = address)),
        )
        assertEquals(
            emptyList<FindingCode>(),
            codes(content(IdentifierScheme.OVERSEAS_REGISTRATION to "REG-1", name = "A", address = address)),
        )
        assertEquals(listOf(FindingCode.MDM_002), codes(content(name = "A", address = address)))
    }

    @Test
    fun `an absent name, address line 1 or postal code is MDM-006, blank counts as absent`() {
        val uen = IdentifierScheme.UEN to "201954321B"
        assertEquals(
            listOf(FindingCode.MDM_006),
            codes(content(uen, name = "A", address = PostalAddress("1 Road", null, null))),
        )
        assertEquals(
            listOf(FindingCode.MDM_006),
            codes(content(uen, name = "A", address = PostalAddress("1 Road", null, "  "))),
        )
        assertEquals(
            listOf(FindingCode.MDM_006),
            codes(content(uen, name = "A", address = PostalAddress(null, null, "048616"))),
        )
        assertEquals(
            listOf(FindingCode.MDM_006),
            codes(content(uen, name = null, address = PostalAddress("1 Road", null, "048616"))),
        )
        assertEquals(listOf(FindingCode.MDM_006, FindingCode.MDM_006), codes(content(uen, name = "A")))
        // MDM-004 and MDM-006 together: NA in the UEN and name, line 1 real, no postal code
        val both = content(IdentifierScheme.NONE to "NA", name = "NA", address = PostalAddress("1 Road", null, null))
        assertEquals(listOf(FindingCode.MDM_004, FindingCode.MDM_006), codes(both))
    }

    private val provenanceChange =
        change(
            IdentifierScheme.UEN to "201954321B",
            arcs = true,
            schemes = listOf(SchemeInput("MES", LocalDate.of(2026, 1, 1), null, null)),
        )

    private fun providedByOf(origin: WriteOrigin) =
        when (origin) {
            WriteOrigin.OPERATOR -> ProvidedBy.INTERNAL
            WriteOrigin.COUNTERPARTY_LINK -> ProvidedBy.COUNTERPARTY
            WriteOrigin.IMPORT -> ProvidedBy.MIGRATED
        }

    @Test
    fun `provenance follows the origin in every part of the record`() {
        WriteOrigin.entries.forEach { origin ->
            val c = null.after(provenanceChange, "EV-1", origin, now)
            val expected = providedByOf(origin)
            assertEquals(expected, c.providedBy)
            assertEquals(
                expected,
                c.identifiers
                    .single()
                    .provenance.providedBy,
            )
            assertEquals(expected, c.arcs!!.provenance.providedBy)
            assertEquals(
                expected,
                c.schemes
                    .single()
                    .provenance.providedBy,
            )
            assertEquals(
                "EV-1",
                c.schemes
                    .single()
                    .provenance.evidenceRef.value,
            )
            assertEquals(
                now,
                c.identifiers
                    .single()
                    .provenance.capturedAt,
            )
        }
    }

    @Test
    fun `MIGRATED cannot be declared and a write needs its evidence`() {
        assertThrows(IllegalArgumentException::class.java) {
            Provenance.declared(ProvidedBy.MIGRATED, EvidenceRef("E"), now)
        }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { null.after(provenanceChange, " ", WriteOrigin.OPERATOR, now) }
    }

    private val road = PostalAddress("1 Road", null, "048616")

    @Test
    fun `a write keeps what it does not mention and merges identifiers by scheme`() {
        val written =
            change(
                IdentifierScheme.UEN to "U-1",
                IdentifierScheme.GSTN to "G-1",
                name = "Old",
                address = road,
                arcs = false,
            )
        val first = null.after(written, "EV-1", WriteOrigin.OPERATOR, now)
        val update = change(IdentifierScheme.UEN to "U-2", name = "New")
        val second = first.after(update, "EV-2", WriteOrigin.COUNTERPARTY_LINK, now.plusSeconds(60))
        assertEquals(setOf("U-2", "G-1"), second.identifiers.map { it.value }.toSet())
        assertEquals(
            ProvidedBy.INTERNAL,
            second.identifiers
                .single {
                    it.scheme == IdentifierScheme.GSTN
                }.provenance.providedBy,
        )
        assertEquals(
            ProvidedBy.COUNTERPARTY,
            second.identifiers
                .single {
                    it.scheme == IdentifierScheme.UEN
                }.provenance.providedBy,
        )
        assertEquals("New", second.legalName)
        assertEquals(road, second.address)
        assertEquals(false, second.arcs!!.agreed)
        assertEquals("EV-1", second.arcs.provenance.evidenceRef.value)
        // the top-level providedBy is that of the most recently captured identifier
        assertEquals(ProvidedBy.COUNTERPARTY, second.providedBy)
        // the earlier content is a value: the later write does not change it
        assertEquals("Old", first.legalName)
        assertEquals(setOf("U-1", "G-1"), first.identifiers.map { it.value }.toSet())
    }

    @Test
    fun `a status is in force from its first to its last day, both included`() {
        val d1 = LocalDate.of(2026, 1, 1)
        val d2 = LocalDate.of(2026, 3, 31)
        val c =
            null.after(
                change(IdentifierScheme.UEN to "U-1", schemes = listOf(SchemeInput("MES", d1, d2, "E"))),
                "EV",
                WriteOrigin.OPERATOR,
                now,
            )
        assertFalse(c.schemesInForceOn(d1.minusDays(1)).isNotEmpty())
        assertTrue(c.schemesInForceOn(d1).isNotEmpty())
        assertTrue(c.schemesInForceOn(d2).isNotEmpty())
        assertFalse(c.schemesInForceOn(d2.plusDays(1)).isNotEmpty())
        assertThrows(
            IllegalArgumentException::class.java,
        ) { SchemeStatus("MES", d2, d1, c.identifiers.single().provenance) }
    }

    private fun node(
        ref: String,
        kind: String,
        parent: String?,
        uen: String? = null,
        gstn: String? = null,
    ) = StructureNode(ref, kind, parent, retired = false, uen = uen, gstn = gstn, structureVersion = 1)

    private val nodes =
        listOf(
            node("TEN", "TENANT", null),
            node("GRP", "GROUP", "TEN", gstn = "M9"),
            node("X", "ENTITY", "GRP", "UX"),
            node("Y", "ENTITY", "GRP", "UY"),
            node("Z", "ENTITY", "TEN", "UZ"),
        )
    private val tenantOnly = Sameness(sameTenant = true, sameGroup = false)

    private fun changed(
        ref: String,
        change: (StructureNode) -> StructureNode,
    ) = nodes.map { if (it.nodeRef == ref) change(it) else it }

    @Test
    fun `sameTenant and sameGroup follow the UEN and the parent of the nodes`() {
        assertEquals(Sameness(sameTenant = true, sameGroup = true), samenessOf("X", "UY", nodes))
        assertEquals(tenantOnly, samenessOf("X", "UZ", nodes))
        assertEquals(Sameness.NEITHER, samenessOf("X", null, nodes))
        assertEquals(tenantOnly, samenessOf("UNKNOWN", "UY", nodes))
    }

    @Test
    fun `the UEN is compared exactly`() {
        assertEquals(Sameness.NEITHER, samenessOf("X", "UY ", nodes))
        assertEquals(Sameness.NEITHER, samenessOf("X", "uy", nodes))
    }

    @Test
    fun `a retired node or one that is not an entity gives neither flag`() {
        assertEquals(Sameness.NEITHER, samenessOf("X", "UY", changed("Y") { it.copy(retired = true) }))
        assertEquals(Sameness.NEITHER, samenessOf("X", "UY", changed("Y") { it.copy(kind = "BUSINESS_UNIT") }))
    }

    @Test
    fun `a parent that is no group or holds no GSTN gives sameTenant only`() {
        assertEquals(tenantOnly, samenessOf("X", "UY", changed("GRP") { it.copy(gstn = null) }))
        assertEquals(tenantOnly, samenessOf("X", "UY", changed("GRP") { it.copy(kind = "TENANT") }))
    }
}
