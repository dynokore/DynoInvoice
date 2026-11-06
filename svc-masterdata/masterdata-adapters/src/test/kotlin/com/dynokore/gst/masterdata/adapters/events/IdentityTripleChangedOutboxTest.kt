package com.dynokore.gst.masterdata.adapters.events

import com.dynokore.gst.masterdata.domain.identity.ChangeState
import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.IdentityTriple
import com.dynokore.gst.masterdata.domain.identity.PendingChange
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.gst.masterdata.domain.identity.TripleVersion
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.lang.reflect.Proxy
import java.time.LocalDate

/**
 * INV-T-16429 (INV-REQ-1135, INV-Q-16401 A): the `IdentityTripleChanged` mapping and its schema in the pinned bundle.
 */
class IdentityTripleChangedOutboxTest {
    private val mapper = JsonMapper.builder().build()
    private val schema =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(
            checkNotNull(
                javaClass.classLoader.getResourceAsStream(
                    "gst/contracts/masterdata/schemas/identity-triple-changed-payload.schema.json",
                ),
            ).use { it.readBytes().toString(Charsets.UTF_8) },
        )

    private val outbox =
        IdentityTripleChangedOutbox(
            outbox = stub(),
            mapper = mapper,
            tenants = stub(),
        )

    private inline fun <reified T : Any> stub(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, _ ->
            error("not expected: ${m.name}")
        } as T

    private fun change(from: LocalDate) =
        PendingChange(
            "ITC-1",
            TripleVersion(
                EntityRef("ENT-1"),
                Constitution("SECTION_33_2_AGENT"),
                IdentityTriple("0195:U-1", "U-1", "G-1"),
                from,
            ),
            PersonRef("IDN-ADMIN"),
            ChangeState.CONFIRMED,
        )

    private fun errors(json: String) = schema.validate(json, InputFormat.JSON)

    @Test
    fun `INV-AC-16047 the payload of a forward-dated change validates against the IdentityTripleChanged schema`() {
        val change = change(LocalDate.now().plusDays(30))
        val payload = outbox.payload(change)
        assertThat(payload.get("entityRef").asString()).isEqualTo(change.version.entity.value)
        assertThat(payload.get("constitution").asString()).isEqualTo(change.version.constitution.code)
        assertThat(payload.get("effectiveFrom").asString()).isEqualTo(change.version.effectiveFrom.toString())
        assertThat(payload.get("changeRef").asString()).isEqualTo(change.changeRef)
        assertThat(payload.propertyNames().toSet())
            .isEqualTo(setOf("entityRef", "constitution", "effectiveFrom", "changeRef"))
        assertThat(errors(payload.toString())).isEmpty()
    }

    @Test
    fun `INV-AC-16047 the CHANNEL of the new class is not the look-alike masterdataIdentityTripleCorrected`() {
        assertThat(IdentityTripleChangedOutbox.CHANNEL).isEqualTo("masterdataIdentityTripleChanged")
        assertThat(IdentityTripleChangedOutbox.CHANNEL).isNotEqualTo(IdentityTripleCorrectedOutbox.CHANNEL)
        assertThat(IdentityTripleChangedOutbox.EVENT_TYPE)
            .isEqualTo("com.dynokore.gst.masterdata.IdentityTripleChanged.v1")
    }

    @Test
    fun `INV-AC-16047 an entityRef of 41 characters is refused by the schema check`() {
        val json =
            """{"entityRef":"${"E".repeat(41)}","constitution":"C","effectiveFrom":"2026-01-01","changeRef":"ITC-1"}"""
        assertThat(errors(json)).isNotEmpty()
        assertThat(errors(json.replace("E".repeat(41), "E".repeat(40)))).isEmpty()
    }
}
