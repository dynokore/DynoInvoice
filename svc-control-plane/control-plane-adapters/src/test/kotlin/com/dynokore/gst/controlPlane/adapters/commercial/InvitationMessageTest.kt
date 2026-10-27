package com.dynokore.gst.controlPlane.adapters.commercial

import com.dynokore.gst.controlPlane.adapters.events.ControlPlaneEventCatalogue
import com.dynokore.gst.controlPlane.adapters.events.InvitationMessagesOutbox
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.platform.outboxandqueue.EmissionNotPermitted
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.networknt.schema.InputFormat
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.io.File

/** The invitation MessageRequested that provisionTenant writes to the outbox (INV-Q-8677 A). */
class InvitationMessageTest {
    private val written = ArrayList<OutboundEvent>()
    private val outbox =
        object : OutboxWriter {
            override fun append(
                events: List<OutboundEvent>,
                context: EventContext,
            ): List<Envelope> {
                events.forEach { ControlPlaneEventCatalogue.guard().require(it.type) }
                written += events
                return emptyList()
            }
        }
    private val json = JsonMapper.builder().build()
    private val schema =
        File(System.getProperty("gst.contracts.bundle.dir"), "access/schemas/message-requested-payload.schema.json")

    private fun emit() =
        InvitationMessagesOutbox(outbox).request(
            CommercialRef("CTR-1"),
            "admin@acme.example",
            "signed-invitation-ref",
            RequestContext("corr", "cause"),
        )

    @Test
    fun `the event is MessageRequested v1 on its topic and the guard accepts it`() {
        emit()
        written.single().type shouldBe "com.dynokore.gst.access.MessageRequested.v1"
        ControlPlaneEventCatalogue
            .catalogue()
            .let { ControlPlaneEventCatalogue.guard().require(written.single().type).topic } shouldBe
            "gst.access.message-requested.v1"
    }

    @Test
    fun `data validates against the pinned schema and holds one recipient, EMAIL and the link`() {
        emit()
        val data = written.single().dataJson
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(schema.readText())
            .validate(data, InputFormat.JSON)
            .isEmpty() shouldBe true
        val node = json.readTree(data)
        json.readValue(data, Map::class.java)["recipients"] shouldBe listOf("admin@acme.example")
        node.get("channel").asString() shouldBe "EMAIL"
        node.get("template").asString() shouldBe "first-administrator-invitation"
        node.get("linkReference").asString() shouldBe "signed-invitation-ref"
        node.propertyNames().asSequence().toSet() shouldBe setOf("template", "recipients", "channel", "linkReference")
    }

    @Test
    fun `the guard still refuses an uncatalogued type and the v2 look-alike`() {
        val guard = ControlPlaneEventCatalogue.guard()
        shouldThrow<EmissionNotPermitted> { guard.require("com.dynokore.gst.access.MessageRequested.v2") }
        shouldThrow<EmissionNotPermitted> { guard.require("com.dynokore.gst.access.messagerequested.v1") }
        shouldThrow<EmissionNotPermitted> { guard.require("com.dynokore.gst.access.AlertRaised.v1") }
    }
}
