package com.dynokore.gst.access.adapters.events

import com.dynokore.gst.access.application.link.MessageChannel
import com.dynokore.gst.access.application.link.MessageRequest
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
 * A test per published channel (INV-T-17453): both messages of one issue, as the real mapper appends them, validate
 * against the schema of `accessMessageRequested` in the pinned contract.
 */
class MessageRequestedPayloadTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val appended = mutableListOf<OutboundEvent>()
    private val writer =
        object : OutboxWriter {
            override fun append(
                events: List<OutboundEvent>,
                context: EventContext,
            ): List<Envelope> {
                appended += events
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
    private val messages = OutboxLinkMessages(writer, tenants, JsonMapper.builder().build())
    private val schema =
        checkNotNull(
            javaClass.classLoader.getResourceAsStream(
                "gst/contracts/access/schemas/message-requested-payload.schema.json",
            ),
        ).use { it.readBytes().toString(Charsets.UTF_8) }
            .let { SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(it) }

    private fun issue() =
        messages.filedUnder(EntityRef("ENT-1")) {
            messages.request(MessageRequest("signed-link", "a@example.sg", MessageChannel.EMAIL, "LNK-1", null))
            messages.request(
                MessageRequest("signed-link-passcode", "a@example.sg", MessageChannel.EMAIL, null, "sealed"),
            )
        }

    @Test
    fun `both messages of one issue validate against the schema, one with linkReference and one with sealedPasscode`() {
        issue()
        assertEquals(2, appended.size)
        assertTrue(appended.all { it.type == OutboxLinkMessages.EVENT_TYPE })
        appended.forEach { assertTrue(schema.validate(it.dataJson, InputFormat.JSON).isEmpty(), it.dataJson) }
        assertTrue(appended[0].dataJson.contains("\"linkReference\":\"LNK-1\""))
        assertTrue(appended[1].dataJson.contains("\"sealedPasscode\":\"sealed\""))
    }

    @Test
    fun `a copy of the payload without template does not validate, so the check can fail`() {
        issue()
        val node = JsonMapper.builder().build().readTree(appended[0].dataJson) as tools.jackson.databind.node.ObjectNode
        node.remove("template")
        assertTrue(schema.validate(node.toString(), InputFormat.JSON).isNotEmpty())
    }
}
