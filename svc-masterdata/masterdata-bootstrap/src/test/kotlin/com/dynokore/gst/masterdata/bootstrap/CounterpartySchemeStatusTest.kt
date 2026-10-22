package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.contract.model.ApprovedScheme
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyChange
import com.dynokore.gst.masterdata.domain.counterparty.SchemeInput
import com.dynokore.gst.masterdata.domain.counterparty.WriteOrigin
import com.dynokore.platform.partyregistry.IdentifierScheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The approved scheme status and the ARCS written-agreement default, held on the counterparty with provenance and read
 * at the transaction date (INV-REQ-2433, INV-REQ-2434, INV-REQ-2445, INV-REQ-2446). The Wave 3 part of INV-AC-2503 (the
 * recorded line) is canonical-svc's.
 */
class CounterpartySchemeStatusTest {
    private val d1 = LocalDate.of(2026, 1, 1)
    private val d2 = LocalDate.of(2026, 3, 31)

    private fun scheme(
        name: String,
        from: LocalDate,
        to: LocalDate? = null,
        evidence: String? = null,
    ) = ApprovedScheme(name, from, to, evidence)

    @Test
    fun `INV-AC-8375 INV-AC-2503 resolution returns the approved scheme items in force at the transaction date`() =
        runCounterparty { env ->
            val entity = unique("ENT")
            env.triple(entity)
            val (a, b, c) = listOf(unique("UEN"), unique("UEN"), unique("UEN"))
            val a1 = scheme("MES", d1, null, "E1")
            env.write(unique("CTP"), env.request("UEN" to a, name = "A", address = ADDRESS, schemes = listOf(a1)))
            val ended = listOf(scheme("MES", d1, d2, "E2"))
            env.write(unique("CTP"), env.request("UEN" to b, name = "B", address = ADDRESS, schemes = ended))
            env.write(unique("CTP"), env.request("UEN" to c, name = "C", address = ADDRESS))
            val resultA = env.resolve(entity, "UEN", a, TRANSACTION_DATE)
            assertThat(resultA.approvedSchemes).containsExactly(a1)
            assertThat(resultA.counterparty!!.approvedSchemes).containsExactly(a1)
            assertThat(env.resolve(entity, "UEN", b, TRANSACTION_DATE).approvedSchemes).isNullOrEmpty()
            val resultC = env.resolve(entity, "UEN", c, TRANSACTION_DATE)
            assertThat(resultC.approvedSchemes).isNullOrEmpty()
            assertThat(resultC.findings).isNullOrEmpty()
            val early = env.resolve(entity, "UEN", a, d1.minusDays(1))
            assertThat(early.approvedSchemes).describedAs("before it starts").isNullOrEmpty()
        }

    @Test
    fun `INV-AC-8376 INV-AC-2503 resolution uses the status in force at the transaction date, not the current one`() =
        runCounterparty { env ->
            val entity = unique("ENT")
            env.triple(entity)
            val party = unique("CTP")
            val uen = unique("UEN")
            val first = scheme("MES", d1, d2)
            val request =
                env.request(
                    "UEN" to uen,
                    name = "S",
                    address = ADDRESS,
                    evidence = "REQ-EV",
                    schemes = listOf(first),
                )
            env.write(party, request)
            val old = scheme("MES", d1, d2, "E-OLD")
            val next = scheme("MES", d2.plusDays(1), null, "E-NEW")
            env.write(party, env.request("UEN" to uen, schemes = listOf(old, next)))
            val t1 = LocalDate.of(2026, 2, 1)
            val t2 = LocalDate.of(2026, 5, 1)
            assertThat(env.resolve(entity, "UEN", uen, t1).approvedSchemes).containsExactly(old)
            assertThat(env.resolve(entity, "UEN", uen, t2).approvedSchemes).containsExactly(next)
            // an item without its own evidence reference holds the evidence reference of its write
            val firstItem =
                env
                    .history(party)
                    .items
                    .first()
                    .counterparty.approvedSchemes!!
                    .single()
            assertThat(firstItem.evidenceRef).isEqualTo("REQ-EV")
            val stored =
                env.rows(
                    "counterparty_scheme_status",
                    party,
                    "provided_by, evidence_ref <> ''",
                    "party_version = 2",
                )
            assertThat(stored)
                .containsExactly(listOf("INTERNAL", "t"), listOf("INTERNAL", "t"))
        }

    @Test
    fun `INV-AC-8376 INV-AC-2503 the import path stores the status MIGRATED with evidence`() =
        runCounterparty { env ->
            val entity = unique("ENT")
            env.triple(entity)
            val imported = unique("CTP")
            val importedUen = unique("UEN")
            val change =
                CounterpartyChange(
                    env.identifiers(IdentifierScheme.UEN to importedUen),
                    null,
                    null,
                    null,
                    listOf(SchemeInput("AISS", d1, null, null)),
                )
            env.recorded(imported, WriteOrigin.IMPORT, "IMPORT-9", change)
            assertThat(env.rows("counterparty_scheme_status", imported, "provided_by, evidence_ref"))
                .containsExactly(listOf("MIGRATED", "IMPORT-9"))
            assertThat(
                env
                    .resolve(entity, "UEN", importedUen)
                    .approvedSchemes!!
                    .single()
                    .scheme,
            ).isEqualTo("AISS")
            val plain = unique("UEN")
            env.write(unique("CTP"), env.request("UEN" to plain, name = "P", address = ADDRESS))
            assertThat(env.resolve(entity, "UEN", plain).approvedSchemes).isNullOrEmpty()
        }

    @Test
    fun `INV-AC-8377 the ARCS default is held with evidence and absent when never set`() =
        runCounterparty { env ->
            val entity = unique("ENT")
            env.triple(entity)
            val partyA = unique("CTP")
            val (a, b) = listOf(unique("UEN"), unique("UEN"))
            val never = env.request("UEN" to a, name = "A", address = ADDRESS, arcs = false, evidence = "EV-ARCS")
            env.write(partyA, never)
            env.write(unique("CTP"), env.request("UEN" to b, name = "B", address = ADDRESS))
            val resultA = env.resolve(entity, "UEN", a)
            assertThat(resultA.arcsGstChargeAgreed).isFalse()
            assertThat(resultA.counterparty!!.arcsGstChargeAgreed).isFalse()
            val resultB = env.resolve(entity, "UEN", b)
            assertThat(resultB.arcsGstChargeAgreed).isNull()
            assertThat(resultB.counterparty!!.arcsGstChargeAgreed).isNull()
            val columns = "arcs_agreed, arcs_provided_by, arcs_evidence_ref, arcs_captured_at is not null"
            assertThat(env.rows("counterparty_version", partyA, columns))
                .containsExactly(listOf("f", "INTERNAL", "EV-ARCS", "t"))
        }

    @Test
    fun `INV-AC-2504 INV-AC-8378 a later change of the ARCS default does not alter a recorded version`() =
        runCounterparty { env ->
            val entity = unique("ENT")
            env.triple(entity)
            val party = unique("CTP")
            val uen = unique("UEN")
            env.write(party, env.request("UEN" to uen, name = "A", address = ADDRESS, arcs = false, evidence = "EV-1"))
            val recorded =
                env
                    .history(party)
                    .items
                    .single()
                    .counterparty
            assertThat(recorded.arcsGstChargeAgreed).isFalse()
            env.write(party, env.request("UEN" to uen, arcs = true, evidence = "EV-2"))
            val items = env.history(party).items
            assertThat(items.map { it.version to it.counterparty.arcsGstChargeAgreed })
                .containsExactly(1 to false, 2 to true)
            assertThat(items.first().counterparty).isEqualTo(recorded)
            assertThat(env.resolve(entity, "UEN", uen).arcsGstChargeAgreed).isTrue()
            // a write that does not mention the default keeps the value held, with the provenance it was set with
            env.write(party, env.request("UEN" to uen, name = "A2", evidence = "EV-3"))
            assertThat(env.resolve(entity, "UEN", uen).arcsGstChargeAgreed).isTrue()
            assertThat(env.rows("counterparty_version", party, "party_version, arcs_agreed, arcs_evidence_ref"))
                .containsExactly(listOf("1", "f", "EV-1"), listOf("2", "t", "EV-2"), listOf("3", "t", "EV-2"))
        }
}
