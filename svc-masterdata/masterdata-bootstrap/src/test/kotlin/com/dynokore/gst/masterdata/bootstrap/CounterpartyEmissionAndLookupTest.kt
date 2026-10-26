package com.dynokore.gst.masterdata.bootstrap

import com.dynokore.gst.masterdata.adapters.events.MasterDataResolvedOutbox
import com.dynokore.gst.masterdata.adapters.events.PublishedChannels
import com.dynokore.gst.masterdata.adapters.http.ApiRefusal
import com.dynokore.gst.masterdata.contract.model.ApprovedScheme
import com.dynokore.gst.masterdata.contract.model.Counterparty
import com.dynokore.gst.masterdata.contract.model.IngestedCounterpartyRequest
import com.dynokore.gst.masterdata.contract.model.ResolveCounterpartyRequestResolvedIdentifiersInner
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * `MasterDataResolved` on every write (INV-Q-8480 A), the entity of the request, the ingestion write path
 * (INV-Q-8486 A), lookup by party reference (INV-Q-8484 A) and the 32-character identifier scheme (INV-Q-8482 A).
 */
class CounterpartyEmissionAndLookupTest {
    private val schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(
            checkNotNull(
                javaClass.classLoader.getResourceAsStream(
                    "gst/contracts/masterdata/schemas/master-data-resolved-payload.schema.json",
                ),
            ).use { it.readBytes().toString(Charsets.UTF_8) },
        )

    private fun CounterpartyEnv.events(party: String): List<Envelope> =
        captured.envelopes.filter { it.subject == party && it.tenant == tenant }

    private fun CounterpartyEnv.ingest(
        party: String,
        request: IngestedCounterpartyRequest,
    ): Counterparty = inTenant { resolution.recordIngestedCounterparty(party, UUID.randomUUID(), request).body!! }

    @Test
    fun `the channel is published and each write emits MasterDataResolved for the entity of the request`() =
        runCounterparty { env ->
            assertThat(PublishedChannels.ALL).contains(MasterDataResolvedOutbox.CHANNEL)
            val party = unique("CTP")
            val uen = unique("UEN")
            env.write(party, env.request("UEN" to uen, name = "A", address = ADDRESS, entity = "E-ONE"))
            env.write(party, env.request("UEN" to uen, name = "B", entity = "E-TWO"))
            val sent = env.events(party)
            assertThat(sent.map { it.entity.value }).containsExactly("E-ONE", "E-TWO")
            sent.forEach {
                assertThat(it.type).isEqualTo(MasterDataResolvedOutbox.EVENT_TYPE)
                assertThat(it.source).isEqualTo("/gst/masterdata-svc")
                assertThat(it.tenant).isEqualTo(env.tenant)
                assertThat(schema.validate(it.dataJson, InputFormat.JSON)).describedAs(it.dataJson).isEmpty()
            }
            assertThat(sent.map { it.docSequence }).containsExactly(1L, 2L)
            val data =
                tools.jackson.databind.json.JsonMapper
                    .builder()
                    .build()
                    .readTree(sent.last().dataJson)
            assertThat(data["partyRef"].asString()).isEqualTo(party)
            assertThat(data["resolvedIdentifiers"].single()["scheme"].asString()).isEqualTo("UEN")
            assertThat(data["resolvedIdentifiers"].single()["providedBy"].asString()).isEqualTo("INTERNAL")
        }

    @Test
    fun `a request without entityRef is 400 SRT-001, stores nothing and emits nothing`() =
        runCounterparty { env ->
            val party = unique("CTP")
            val uen = unique("U")
            val identifiers =
                "\"resolvedIdentifiers\":[{\"scheme\":\"UEN\",\"value\":\"$uen\"}],\"evidenceRef\":\"E-1\""
            listOf(
                "absent" to "{$identifiers}",
                "blank" to "{\"entityRef\":\"  \",$identifiers}",
                "look-alike lower-case property" to "{\"entityref\":\"$ENTITY\",$identifiers}",
            ).forEach { (label, body) ->
                val (status, text) = env.post(party, body)
                assertThat(status).describedAs(label).isEqualTo(400)
                assertThat(text).describedAs(label).contains("SRT-001")
            }
            assertThat(env.rows("counterparty_version", party, "party_version")).isEmpty()
            assertThat(env.events(party)).isEmpty()
            val (status, _) = env.post(party, "{\"entityRef\":\"$ENTITY\",$identifiers}")
            assertThat(status).isEqualTo(200)
            assertThat(
                env
                    .events(party)
                    .single()
                    .entity.value,
            ).isEqualTo(ENTITY)
        }

    @Test
    fun `INV-AC-8376 the ingestion write path stores approved schemes and the ARCS default with evidence and emits`() =
        runCounterparty { env ->
            val party = unique("CTP")
            val uen = unique("UEN")
            val from = LocalDate.of(2026, 1, 1)
            val answer =
                env.ingest(
                    party,
                    IngestedCounterpartyRequest(
                        entityRef = "E-ING",
                        evidenceRef = "DOC-77",
                        providedBy = IngestedCounterpartyRequest.ProvidedBy.COUNTERPARTY,
                        resolvedIdentifiers = listOf(ResolveCounterpartyRequestResolvedIdentifiersInner("UEN", uen)),
                        arcsGstChargeAgreed = true,
                        approvedSchemes = listOf(ApprovedScheme("AISS", from, null)),
                    ),
                )
            assertThat(answer.providedBy).isEqualTo(Counterparty.ProvidedBy.COUNTERPARTY)
            assertThat(answer.arcsGstChargeAgreed).isTrue()
            val item = answer.approvedSchemes!!.single()
            assertThat(item.scheme to item.evidenceRef).isEqualTo("AISS" to "DOC-77")
            assertThat(env.rows("counterparty_scheme_status", party, "provided_by, evidence_ref"))
                .containsExactly(listOf("COUNTERPARTY", "DOC-77"))
        }

    @Test
    fun `INV-AC-8376 a second ingestion write is a new version and each one emits for its entity`() =
        runCounterparty { env ->
            val party = unique("CTP")
            val uen = unique("UEN")
            env.ingest(
                party,
                IngestedCounterpartyRequest(
                    "E-ING",
                    "DOC-77",
                    IngestedCounterpartyRequest.ProvidedBy.COUNTERPARTY,
                    resolvedIdentifiers = listOf(ResolveCounterpartyRequestResolvedIdentifiersInner("UEN", uen)),
                    arcsGstChargeAgreed = true,
                ),
            )
            // a second write is a new version, never a precondition on what was held
            val second =
                env.ingest(
                    party,
                    IngestedCounterpartyRequest(
                        "E-ING-2",
                        "DOC-78",
                        IngestedCounterpartyRequest.ProvidedBy.INTERNAL,
                        resolvedIdentifiers =
                            listOf(ResolveCounterpartyRequestResolvedIdentifiersInner("GSTN", "G-$uen")),
                    ),
                )
            assertThat(second.providedBy).isEqualTo(Counterparty.ProvidedBy.INTERNAL)
            val page = env.history(party)
            assertThat(page.items.map { it.version to it.current }).containsExactly(1 to false, 2 to true)
            assertThat(
                page.items
                    .first()
                    .counterparty.arcsGstChargeAgreed,
            ).isTrue()
            assertThat(env.events(party).map { it.entity.value }).containsExactly("E-ING", "E-ING-2")
        }

    @Test
    fun `the ingestion write path refuses MIGRATED and a body without its required parts, storing nothing`() =
        runCounterparty { env ->
            val party = unique("CTP")
            listOf(
                "MIGRATED" to "{\"entityRef\":\"E-1\",\"evidenceRef\":\"D\",\"providedBy\":\"MIGRATED\"}",
                "look-alike MIGRATE" to "{\"entityRef\":\"E-1\",\"evidenceRef\":\"D\",\"providedBy\":\"MIGRATE\"}",
                "no providedBy" to "{\"entityRef\":\"E-1\",\"evidenceRef\":\"D\"}",
                "no entityRef" to "{\"evidenceRef\":\"D\",\"providedBy\":\"INTERNAL\"}",
                "no evidenceRef" to "{\"entityRef\":\"E-1\",\"providedBy\":\"INTERNAL\"}",
            ).forEach { (label, body) ->
                assertThat(env.post(party, body, "ingested-details").first).describedAs(label).isEqualTo(400)
            }
            assertThat(env.rows("counterparty_version", party, "party_version")).isEmpty()
            assertThat(env.events(party)).isEmpty()
            val ok = "{\"entityRef\":\"E-1\",\"evidenceRef\":\"D\",\"providedBy\":\"INTERNAL\"}"
            assertThat(env.post(party, ok, "ingested-details").first).isEqualTo(200)
        }

    @Test
    fun `INV-AC-1605 a counterparty of scheme NONE and value NA is found by partyRef and reports MDM-004`() =
        runCounterparty { env ->
            val entity = unique("ENT")
            env.triple(entity)
            val party = unique("IND")
            env.write(party, env.request("NONE" to "NA", name = "NA", address = ADDRESS))
            val found = env.resolve(entity, null, null, partyRef = party)
            assertThat(found.counterparty!!.partyRef).isEqualTo(party)
            assertThat(env.codes(found).map { it.first }).containsExactly("MDM-004")
            // the pair NONE and NA names no one (it is shared by every individual)
            val pair = env.resolve(entity, "NONE", "NA")
            assertThat(pair.counterparty).isNull()
            assertThat(env.codes(pair).map { it.first }).containsExactly("MDM-001")
            val unknown = env.resolve(entity, null, null, partyRef = unique("NOPE"))
            assertThat(unknown.counterparty).isNull()
            assertThat(env.codes(unknown).map { it.first }).containsExactly("MDM-001")
        }

    @Test
    fun `a party named by partyRef beside the pair, or by half of the pair, is 400 and none is the triple only`() =
        runCounterparty { env ->
            val entity = unique("ENT")
            env.triple(entity)

            fun refusal(
                ref: String?,
                scheme: String?,
                value: String?,
            ) = (
                runCatching {
                    env.inTenant {
                        env.resolution.resolveCounterparty(
                            entity,
                            CONSTITUTION.code,
                            TRANSACTION_DATE,
                            ref,
                            scheme,
                            value,
                        )
                    }
                }.exceptionOrNull() as? ApiRefusal
            )?.code
            assertThat(refusal("P-1", "UEN", "U-1")).isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
            assertThat(refusal("P-1", "UEN", null)).isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
            assertThat(refusal("P-1", null, "U-1")).isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
            assertThat(refusal(null, "UEN", null)).isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
            assertThat(refusal(null, null, "U-1")).isEqualTo(ClosedErrorCode.CONTRACT_VIOLATION)
            val none = env.resolve(entity, null, null)
            assertThat(none.counterparty).isNull()
            assertThat(none.findings).isNullOrEmpty()
        }

    @Test
    fun `schemes of 21 characters are stored, a 33-character scheme and a 21-character approved scheme are 400`() =
        runCounterparty { env ->
            val entity = unique("ENT")
            env.triple(entity)
            val party = unique("CTP")
            val peppol = unique("0195:P")
            val overseas = unique("OS")
            env.write(
                party,
                env.request("PEPPOL_PARTICIPANT_ID" to peppol, "OVERSEAS_REGISTRATION" to overseas, name = "O"),
            )
            assertThat(env.rows("counterparty_identifier", party, "scheme, identifier"))
                .contains(listOf("OVERSEAS_REGISTRATION", overseas), listOf("PEPPOL_PARTICIPANT_ID", peppol))
            assertThat(env.resolve(entity, "PEPPOL_PARTICIPANT_ID", peppol).counterparty!!.partyRef).isEqualTo(party)
            assertThat(env.resolve(entity, "OVERSEAS_REGISTRATION", overseas).counterparty!!.partyRef).isEqualTo(party)
            val readBack =
                env
                    .history(party)
                    .items
                    .single()
                    .counterparty.resolvedIdentifiers
                    .map { it.scheme }
            assertThat(readBack).containsExactlyInAnyOrder("PEPPOL_PARTICIPANT_ID", "OVERSEAS_REGISTRATION")

            fun body(
                scheme: String,
                approved: String = "",
            ) = "{\"entityRef\":\"$ENTITY\",\"evidenceRef\":\"E\"," +
                "\"resolvedIdentifiers\":[{\"scheme\":\"$scheme\",\"value\":\"v\"}]$approved}"
            assertThat(env.post(unique("CTP"), body("S".repeat(33))).first).isEqualTo(400)
            val twentyOne = "A".repeat(21)
            val approved = ",\"approvedSchemes\":[{\"scheme\":\"$twentyOne\",\"effectiveFrom\":\"2026-01-01\"}]"
            assertThat(env.post(unique("CTP"), body("UEN", approved)).first).isEqualTo(400)
            val ok = ",\"approvedSchemes\":[{\"scheme\":\"${"A".repeat(20)}\",\"effectiveFrom\":\"2026-01-01\"}]"
            assertThat(env.post(unique("CTP"), body("UEN", ok)).first).isEqualTo(200)
        }
}
