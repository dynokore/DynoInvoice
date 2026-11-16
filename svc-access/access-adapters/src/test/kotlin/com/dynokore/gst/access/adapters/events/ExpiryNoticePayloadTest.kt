package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.grant.ExpiryNoticeChannel
import com.dynokore.gst.access.application.grant.ExpiryNoticeRequest
import com.dynokore.gst.access.domain.grant.EntityRef
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * The grant expiry notice as the shared publisher appends it (INV-T-15799, ruling INV-Q-15790 A): the payload is the
 * `message-requested-payload` schema of the pinned contract, with no link reference and no passcode.
 */
class ExpiryNoticePayloadTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val appended = mutableListOf<OutboundEvent>()
    private val contexts = mutableListOf<EventContext>()
    private val writer =
        object : OutboxWriter {
            override fun append(
                events: List<OutboundEvent>,
                context: EventContext,
            ): List<Envelope> {
                appended += events
                contexts += context
                return emptyList()
            }
        }
    private val tenants =
        object : TenantContext {
            override fun current() = tenant

            override fun currentActor() = "system:access-svc"

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val mapper = JsonMapper.builder().build()
    private val messages = OutboxLinkMessages(writer, tenants, mapper)
    private val schema =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream(
                "gst/contracts/access/schemas/message-requested-payload.schema.json",
            ),
        ).use { it.readBytes().toString(Charsets.UTF_8) }
            .let { SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(it) }

    private fun notice(vararg to: String) =
        messages.request(
            ExpiryNoticeRequest(
                ExpiryNoticeRequest.TEMPLATE,
                to.toList(),
                ExpiryNoticeChannel.EMAIL,
                EntityRef("ENT-9"),
            ),
        )

    @Test
    fun `an expiry notice validates against the published message-requested-payload schema`() {
        notice("holder@example.sg", "admin@example.sg")
        val event = appended.single()
        assertEquals(OutboxLinkMessages.EVENT_TYPE, event.type)
        assertTrue(schema.validate(event.dataJson, InputFormat.JSON).isEmpty(), event.dataJson)
        val node = mapper.readTree(event.dataJson)
        assertEquals("grant-expiry-notice", node.path("template").asString())
        assertEquals("EMAIL", node.path("channel").asString())
        assertFalse(node.has("linkReference"))
        assertFalse(node.has("sealedPasscode"))
    }

    @Test
    fun `the recipients of the payload equal the addresses of the request, in order`() {
        notice("holder@example.sg", "admin@example.sg")
        val node = mapper.readTree(appended.single().dataJson)
        assertEquals(
            listOf("holder@example.sg", "admin@example.sg"),
            node.path("recipients").let { n ->
                (
                    0 until
                        n.size()
                ).map { n[it].asString() }
            },
        )
    }

    @Test
    fun `a request for an entity is filed under that entity and no filedUnder scope is needed`() {
        notice("holder@example.sg")
        assertEquals("ENT-9", contexts.single().entity.value)
        assertEquals(tenant, contexts.single().tenant)
        assertEquals("entity:ENT-9", contexts.single().aggregateKey)
    }

    @Test
    fun `a payload of a notice without template does not validate, so the check can fail`() {
        notice("holder@example.sg")
        val node = mapper.readTree(appended.single().dataJson) as tools.jackson.databind.node.ObjectNode
        node.remove("recipients")
        assertTrue(schema.validate(node.toString(), InputFormat.JSON).isNotEmpty())
    }
}
