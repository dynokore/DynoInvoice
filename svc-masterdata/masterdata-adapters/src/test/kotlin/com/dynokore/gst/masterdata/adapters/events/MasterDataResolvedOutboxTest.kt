package com.dynokore.gst.masterdata.adapters.events

import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyContent
import com.dynokore.gst.masterdata.domain.counterparty.CounterpartyVersion
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.platform.partyregistry.EvidenceRef
import com.dynokore.platform.partyregistry.Identifier
import com.dynokore.platform.partyregistry.IdentifierScheme
import com.dynokore.platform.partyregistry.Provenance
import com.dynokore.platform.partyregistry.ProvidedBy
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID

/** INV-T-21320 (INV-REQ-941): the `MasterDataResolved` mapping writes `docUuids` and the pinned schema accepts it. */
class MasterDataResolvedOutboxTest {
    private val schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(
            checkNotNull(
                javaClass.classLoader.getResourceAsStream(
                    "gst/contracts/masterdata/schemas/master-data-resolved-payload.schema.json",
                ),
            ).use { it.readBytes().toString(Charsets.UTF_8) },
        )
    private val outbox = MasterDataResolvedOutbox(stub(), JsonMapper.builder().build(), stub())

    private inline fun <reified T : Any> stub(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
            error("not expected: ${m.name}")
        } as T

    private val d1 = UUID.fromString("0A1B2C3D-0000-4000-8000-00000000000A")
    private val d2 = UUID.fromString("0a1b2c3d-0000-4000-8000-00000000000b")
    private val d3 = UUID.fromString("0a1b2c3d-0000-4000-8000-00000000000c")
    private val version =
        CounterpartyVersion(
            PartyRef("P"),
            1,
            Instant.parse("2026-11-08T00:00:00Z"),
            CounterpartyContent(
                listOf(
                    Identifier(
                        IdentifierScheme.UEN,
                        "201912345K",
                        Provenance.declared(ProvidedBy.INTERNAL, EvidenceRef("EV-1"), Instant.parse("2026-11-08T00:00:00Z")),
                    ),
                ),
                null,
                null,
                null,
                emptyList(),
                ProvidedBy.INTERNAL,
            ),
        )

    private fun docs(payload: ObjectNode): List<String> =
        payload.get("docUuids").let { array -> (0 until array.size()).map { array.get(it).asString() } }

    private fun errors(json: String) = schema.validate(json, InputFormat.JSON)

    @Test
    fun `INV-AC-21319 the payload for three documents holds docUuids in list order as lower-case strings and validates`() {
        val payload = outbox.payload(version, listOf(d3, d1, d2))
        assertThat(docs(payload)).isEqualTo(
            listOf(
                "0a1b2c3d-0000-4000-8000-00000000000c",
                "0a1b2c3d-0000-4000-8000-00000000000a",
                "0a1b2c3d-0000-4000-8000-00000000000b",
            ),
        )
        assertThat(payload.get("partyRef").asString()).isEqualTo("P")
        assertThat(payload.get("resolvedIdentifiers").size()).isEqualTo(1)
        assertThat(payload.get("resolvedIdentifiers").get(0).get("value").asString()).isEqualTo("201912345K")
        assertThat(errors(payload.toString())).isEmpty()
    }

    @Test
    fun `INV-AC-21319 the payload for one document holds that document alone`() {
        val payload = outbox.payload(version, listOf(d1))
        assertThat(docs(payload))
            .isEqualTo(listOf("0a1b2c3d-0000-4000-8000-00000000000a"))
        assertThat(errors(payload.toString())).isEmpty()
    }

    @Test
    fun `INV-AC-21319 the payload for the empty list has no docUuids key at all and validates`() {
        val payload = outbox.payload(version, emptyList())
        assertThat(payload.has("docUuids")).isFalse()
        assertThat(payload.propertyNames().toSet()).isEqualTo(setOf("partyRef", "resolvedIdentifiers"))
        assertThat(errors(payload.toString())).isEmpty()
    }
}
