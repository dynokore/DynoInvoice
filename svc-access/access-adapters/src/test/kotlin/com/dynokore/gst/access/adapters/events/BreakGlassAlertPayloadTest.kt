package com.dynokore.gst.access.adapters.events

import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/**
 * A test for the platform event this service publishes for ACC-002 (INV-Q-8839 A): the payload validates against the
 * `break-glass-alert-raised-payload` schema of platform-contracts, the event is filed under the real tenant with the
 * tenant-wide entity, and the domain type with the tenant-wide entity is refused by the envelope.
 */
class BreakGlassAlertPayloadTest {
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
    private val alerts = BreakGlassAlertOutbox(writer, tenants, JsonMapper.builder().build())
    private val schema =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream(
                "gst/contracts/service-runtime/schemas/break-glass-alert-raised-payload.schema.json",
            ),
        ).use { it.readBytes().toString(Charsets.UTF_8) }
            .let { SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(it) }

    @Test
    fun `an ACC-002 alert is the platform event BreakGlassAlertRaised, valid, under the real tenant`() {
        alerts.raise("ACC-002", "UNLOCK", "BG-0003")
        alerts.raise("ACC-002", "REPLAY", "BG-0003")
        assertEquals(2, appended.size)
        appended.forEach { (events, context) ->
            val event = events.single()
            assertEquals("com.dynokore.gst.platform.BreakGlassAlertRaised.v1", event.type)
            assertTrue(schema.validate(event.dataJson, InputFormat.JSON).isEmpty(), event.dataJson)
            assertTrue(event.dataJson.contains("\"accCode\":\"ACC-002\""))
            assertEquals(tenant, context.tenant)
            assertEquals(EntityRef.TENANT_WIDE, context.entity)
        }
    }

    @Test
    fun `a payload without the code, the scope or the action, or with an unknown action, does not validate`() {
        assertTrue(schema.validate("""{"scope":"TENANT","action":"UNLOCK"}""", InputFormat.JSON).isNotEmpty())
        assertTrue(schema.validate("""{"accCode":"ACC-002","action":"UNLOCK"}""", InputFormat.JSON).isNotEmpty())
        assertTrue(schema.validate("""{"accCode":"ACC-002","scope":"TENANT"}""", InputFormat.JSON).isNotEmpty())
        val unknown = """{"accCode":"ACC-002","scope":"TENANT","action":"EXPORT"}"""
        assertTrue(schema.validate(unknown, InputFormat.JSON).isNotEmpty())
    }

    @Test
    fun `the same alert written as the domain type with the tenant-wide entity is refused by the envelope`() {
        val refused =
            assertThrows(IllegalArgumentException::class.java) {
                Envelope(
                    id = EventId(UUID.randomUUID().toString()),
                    source = "/gst/access-svc",
                    type = "com.dynokore.gst.access.AccessAlertRaised.v1",
                    subject = "break-glass:BG-0003",
                    time = Instant.now(),
                    tenant = tenant,
                    entity = EntityRef.TENANT_WIDE,
                    docUuid = null,
                    transactionDate = null,
                    docSequence = null,
                    instanceId = "access-svc-0",
                    correlation = CorrelationId("c"),
                    causation = CausationId("c"),
                    dataJson = """{"accCode":"ACC-002","scope":"TENANT"}""",
                )
            }
        assertTrue(refused.message!!.isNotBlank())
    }
}
