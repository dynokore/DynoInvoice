package com.dynokore.gst.access.adapters.events

import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * A test per published channel (INV-T-4938): the ACC alert this service raises validates against the AsyncAPI schema
 * of `accessAccessAlertRaised`, carries the ACC code and the scope, and is filed under the entity the wiring gives it.
 */
class AccessAlertPayloadTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val appended = mutableListOf<Pair<List<OutboundEvent>, EventContext>>()
    private val writer =
        object : OutboxWriter {
            override fun append(
                events: List<OutboundEvent>,
                context: EventContext,
            ): List<Envelope> {
                appended += events to context
                return emptyList()
            }
        }
    private val tenants =
        object : TenantContext {
            override fun current() = tenant

            override fun currentActor() = "user:test"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val alerts =
        AccessAlertOutbox(writer, tenants, JsonMapper.builder().build()) { EntityRef("ENT-1") }
    private val schema =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream(
                "gst/contracts/access/schemas/access-alert-raised-payload.schema.json",
            ),
        ).use { it.readBytes().toString(Charsets.UTF_8) }
            .let { SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(it) }

    @Test
    fun `an ACC-002 alert is the AccessAlertRaised event with the code and the scope, valid against the schema`() {
        alerts.raise("ACC-002", "break-glass:BG-0003")
        val (events, context) = appended.single()
        val event = events.single()
        assertEquals("com.dynokore.gst.access.AccessAlertRaised.v1", event.type)
        assertTrue(schema.validate(event.dataJson, InputFormat.JSON).isEmpty())
        assertTrue(event.dataJson.contains("\"accCode\":\"ACC-002\""))
        assertTrue(event.dataJson.contains("\"scope\":\"break-glass:BG-0003\""))
        assertEquals(EntityRef("ENT-1"), context.entity)
    }

    @Test
    fun `a payload without the code or the scope does not validate, so the check can fail`() {
        assertTrue(schema.validate("""{"scope":"x"}""", InputFormat.JSON).isNotEmpty())
        assertTrue(schema.validate("""{"accCode":"ACC-002"}""", InputFormat.JSON).isNotEmpty())
        assertTrue(schema.validate("""{"accCode":"alert","scope":"x"}""", InputFormat.JSON).isNotEmpty())
    }
}
