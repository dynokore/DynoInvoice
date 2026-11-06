package com.dynokore.gst.masterdata.adapters.events

import com.dynokore.gst.masterdata.domain.identity.ChangeState
import com.dynokore.gst.masterdata.domain.identity.Constitution
import com.dynokore.gst.masterdata.domain.identity.EntityRef
import com.dynokore.gst.masterdata.domain.identity.IdentityTriple
import com.dynokore.gst.masterdata.domain.identity.PendingChange
import com.dynokore.gst.masterdata.domain.identity.PersonRef
import com.dynokore.gst.masterdata.domain.identity.TripleVersion
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.util.UUID

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

    private companion object {
        const val TENANT = "0a000000-0000-4000-8000-00000000000a"
    }

    private val declared = setOf("entityRef", "constitution", "effectiveFrom", "changeRef")

    private fun keys(json: String) = mapper.readTree(json).propertyNames().toSet()

    @Test
    fun `INV-AC-16047 the serialized payload has exactly the four declared members and no accessPointEscalation`() {
        val appended = mutableListOf<OutboundEvent>()
        val writer =
            object : OutboxWriter {
                override fun append(
                    events: List<OutboundEvent>,
                    context: EventContext,
                ): List<Envelope> {
                    appended += events
                    return emptyList()
                }
            }
        val tenants =
            Proxy.newProxyInstance(
                TenantContext::class.java.classLoader,
                arrayOf(TenantContext::class.java),
            ) { _, m, _ ->
                if (m.name.startsWith("current")) {
                    // a value class return is passed as its underlying value through a proxy
                    UUID.fromString(TENANT).let { if (m.returnType == UUID::class.java) it else TenantId(it) }
                } else {
                    error(m.name)
                }
            } as TenantContext
        IdentityTripleChangedOutbox(writer, mapper, tenants).publish(change(LocalDate.now().plusDays(30)))
        val event = appended.single()
        assertThat(event.type).isEqualTo(IdentityTripleChangedOutbox.EVENT_TYPE)
        assertThat(keys(event.dataJson)).isEqualTo(declared)
        // look-alikes: the exact-shape check refuses an extra member and a renamed one
        val base = """{"entityRef":"E","constitution":"C","effectiveFrom":"2026-01-01","changeRef":"R"}"""
        assertThat(keys(base)).isEqualTo(declared)
        assertThat(keys(base.dropLast(1) + ""","accessPointEscalation":true}""")).isNotEqualTo(declared)
        assertThat(keys(base.replace("changeRef", "changeReference"))).isNotEqualTo(declared)
    }
}
