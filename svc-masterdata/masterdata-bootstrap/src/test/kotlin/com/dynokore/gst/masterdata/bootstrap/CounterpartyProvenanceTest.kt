package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.contract.model.Counterparty
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyChange
import com.dynokore.gst.masterdata.domain.counterparty.WriteOrigin
import com.dynokore.platform.partyregistry.IdentifierScheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/** Provenance of every identifier (INV-REQ-314, INV-REQ-1111): who supplied it, the evidence and when. */
class CounterpartyProvenanceTest {
    @Test
    fun `INV-AC-1607 INV-AC-8372 a value entered by the business is INTERNAL with evidence and capture time`() =
        runCounterparty { env ->
            val party = unique("CTP")
            val before = Instant.now()
            val written =
                env.write(
                    party,
                    env.request("UEN" to unique("UEN"), evidence = "LETTER-2026-17", name = "Acme", address = ADDRESS),
                )
            val after = Instant.now()
            val read =
                env
                    .history(party)
                    .items
                    .single()
                    .counterparty
            listOf(written, read).forEach { counterparty ->
                assertThat(counterparty.providedBy).isEqualTo(Counterparty.ProvidedBy.INTERNAL)
                counterparty.resolvedIdentifiers.forEach {
                    val provenance = checkNotNull(it.provenance) { "every identifier carries provenance" }
                    assertThat(provenance.providedBy.value).isEqualTo("INTERNAL")
                    assertThat(provenance.evidenceRef).isEqualTo("LETTER-2026-17")
                    assertThat(
                        provenance.capturedAt.toInstant(),
                    ).isBetween(before.minusSeconds(1), after.plusSeconds(1))
                }
            }
            assertThat(env.rows("counterparty_identifier", party, "provided_by, evidence_ref, captured_at is not null"))
                .containsExactly(listOf("INTERNAL", "LETTER-2026-17", "t"))
        }

    @Test
    fun `INV-AC-357 INV-AC-8372 the rows an applied import writes hold MIGRATED, the part this service produces`() =
        runCounterparty { env ->
            val party = unique("CTP")
            val change =
                CounterpartyChange(
                    env.identifiers(IdentifierScheme.UEN to unique("UEN")),
                    "Migrated Pte Ltd",
                    null,
                    null,
                    null,
                )
            env.recorded(party, WriteOrigin.IMPORT, "IMPORT-2026-10-22", change)
            assertThat(env.rows("counterparty_identifier", party, "provided_by, evidence_ref"))
                .containsExactly(listOf("MIGRATED", "IMPORT-2026-10-22"))
            assertThat(env.rows("counterparty_version", party, "provided_by")).containsExactly(listOf("MIGRATED"))
        }

    @Test
    fun `INV-AC-2109 INV-AC-8373 an imported counterparty carries MIGRATED and the API refuses a caller posting it`() =
        runCounterparty { env ->
            val entity = unique("ENT")
            env.triple(entity)
            val uen = unique("UEN")
            val change = CounterpartyChange(env.identifiers(IdentifierScheme.UEN to uen), "Imp", null, null, null)
            env.recorded(unique("CTP"), WriteOrigin.IMPORT, "IMPORT-1", change)
            val read = env.resolve(entity, "UEN", uen).counterparty!!
            assertThat(read.providedBy).isEqualTo(Counterparty.ProvidedBy.MIGRATED)
            assertThat(read.resolvedIdentifiers.map { it.provenance!!.providedBy.value }).containsOnly("MIGRATED")
            refusedBodies().forEach { (label, body) ->
                val party = unique("CTP-REF")
                val (status, text) = env.post(party, body)
                assertThat(status).describedAs(label).isEqualTo(400)
                assertThat(text).describedAs(label).contains("SRT-001")
                assertThat(env.rows("counterparty_version", party, "party_version")).describedAs(label).isEmpty()
            }
            assertThat(
                env.count(
                    "select count(*) from masterdata.counterparty_identifier " +
                        "where provided_by = 'MIGRATED' and party_ref like 'CTP-REF-%'",
                ),
            ).isZero()
        }

    @Test
    fun `INV-AC-2109 a look-alike key is no provenance key, the value is stored INTERNAL`() =
        runCounterparty { env ->
            val party = unique("CTP-LOOK")
            val (status, _) = env.post(party, body("\"providedByX\":\"MIGRATED\""))
            assertThat(status).isEqualTo(200)
            assertThat(
                env
                    .history(party)
                    .items
                    .single()
                    .counterparty.providedBy,
            ).isEqualTo(Counterparty.ProvidedBy.INTERNAL)
        }

    private fun body(
        extra: String,
        identifierExtra: String = "",
    ) = "{\"resolvedIdentifiers\":[{\"scheme\":\"UEN\",\"value\":\"${unique("U")}\"$identifierExtra}]," +
        "\"entityRef\":\"$ENTITY\",\"evidenceRef\":\"E-1\",$extra}"

    /** Bodies in which a caller supplies provenance, at any depth: each is refused before the controller runs. */
    private fun refusedBodies() =
        mapOf(
            "top-level providedBy" to body("\"providedBy\":\"MIGRATED\""),
            "providedBy null" to body("\"providedBy\":null"),
            "identifier providedBy" to body("\"legalName\":\"A\"", ",\"providedBy\":\"MIGRATED\""),
            "identifier provenance" to
                body(
                    "\"legalName\":\"A\"",
                    ",\"provenance\":{\"providedBy\":\"MIGRATED\",\"evidenceRef\":\"E\"," +
                        "\"capturedAt\":\"2020-01-01T00:00:00Z\"}",
                ),
            "capturedAt" to body("\"capturedAt\":\"2020-01-01T00:00:00Z\""),
            "scheme item providedBy" to
                body(
                    "\"approvedSchemes\":[{\"scheme\":\"MES\",\"effectiveFrom\":\"2026-01-01\"," +
                        "\"providedBy\":\"MIGRATED\"}]",
                ),
        )
}
