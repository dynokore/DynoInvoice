package com.dynokore.gst.ingestion.adapters.events

import com.dynokore.gst.ingestion.application.feedhealth.FeedControl
import com.dynokore.gst.ingestion.application.feedhealth.FeedHealthChange
import com.dynokore.gst.ingestion.application.feedhealth.FeedHealthKey
import com.dynokore.gst.ingestion.application.feedhealth.FeedHealthState
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.File
import java.util.UUID

/**
 * INV-AC-22215 (INV-REQ-815): the outbox adapter publishes every change as `FeedHealthChanged` with the payload of the
 * ingestion AsyncAPI contract plus `stateVersion`, the tenant in the envelope and the key of the counter as aggregate
 * key. The writer is a recorder; the payload is validated against the pinned
 * `feed-health-changed-payload.schema.json` staged under `gst.contract.dir`; a missing property or file fails.
 */
class FeedHealthChangedOutboxTest {
    private class Recorder : OutboxWriter {
        val appended = mutableListOf<Pair<List<OutboundEvent>, EventContext>>()

        override fun append(
            events: List<OutboundEvent>,
            context: EventContext,
        ): List<Envelope> {
            appended += events to context
            return emptyList()
        }
    }

    private val mapper = JsonMapper.builder().build()
    private val tenant = UUID.randomUUID()

    private fun publish(
        control: FeedControl,
        state: FeedHealthState,
        version: Long,
    ): Recorder {
        val recorder = Recorder()
        FeedHealthChangedOutbox(recorder).publish(
            FeedHealthChange(FeedHealthKey(tenant, "F1", "E-001", "2026Q3", control), state, version),
        )
        return recorder
    }

    private fun payload(recorder: Recorder): JsonNode =
        mapper.readTree(
            recorder.appended
                .single()
                .first
                .single()
                .dataJson,
        )

    @Test
    fun `INV-AC-22215 a FAILED change publishes stateVersion 2 and a CLEARED change stateVersion 3`() {
        payload(publish(FeedControl.COUNT, FeedHealthState.FAILED, 2)).let {
            it["state"].asString() shouldBe "FAILED"
            it["stateVersion"].asLong() shouldBe 2
        }
        payload(publish(FeedControl.COUNT, FeedHealthState.CLEARED, 3)).let {
            it["state"].asString() shouldBe "CLEARED"
            it["stateVersion"].asLong() shouldBe 3
        }
    }

    @Test
    fun `INV-AC-22215 the payload carries exactly feed, entityRef, period, control, state and stateVersion`() {
        val node = payload(publish(FeedControl.WATERMARK, FeedHealthState.FAILED, 1))
        node.propertyNames().asSequence().toSet() shouldBe
            setOf("feed", "entityRef", "period", "control", "state", "stateVersion")
        node["feed"].asString() shouldBe "F1"
        node["entityRef"].asString() shouldBe "E-001"
        node["period"].asString() shouldBe "2026Q3"
        node["control"].asString() shouldBe "WATERMARK"
    }

    @Test
    fun `INV-AC-22215 the payload of each control and state validates against the pinned feed-health-changed schema`() {
        val dir = checkNotNull(System.getProperty("gst.contract.dir")) { "system property gst.contract.dir is absent" }
        val file = File(dir, "schemas/feed-health-changed-payload.schema.json")
        check(file.isFile) { "the pinned schema $file does not exist" }
        val registry =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
                it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
            }
        val schema = registry.getSchema(file.readText())
        var checked = 0
        FeedControl.entries.forEach { control ->
            FeedHealthState.entries.forEach { state ->
                val text = publish(control, state, 7).appended.single().first.single().dataJson
                schema.validate(mapper.readTree(text)).map { it.toString() } shouldBe emptyList()
                val node = mapper.readTree(text)
                // the schema leaves stateVersion optional; the producer always sends it
                node["stateVersion"].isIntegralNumber shouldBe true
                (node["stateVersion"].asLong() >= 1) shouldBe true
                checked++
            }
        }
        checked shouldBe 10
    }

    @Test
    fun `INV-AC-22215 a payload without stateVersion is never produced`() {
        FeedControl.entries.forEach {
            payload(publish(it, FeedHealthState.CLEARED, 1)).has("stateVersion") shouldBe true
        }
    }

    @Test
    fun `INV-AC-22215 the event travels under the v1 type and its look-alike v2 is not the type`() {
        val recorder = publish(FeedControl.VALUE, FeedHealthState.FAILED, 1)
        val type =
            recorder.appended
                .single()
                .first
                .single()
                .type
        type shouldBe "com.dynokore.gst.ingestion.FeedHealthChanged.v1"
        type shouldBe FeedHealthChangedOutbox.TYPE
        (type == "com.dynokore.gst.ingestion.FeedHealthChanged.v2") shouldBe false
        FeedHealthChangedOutbox.TOPIC shouldBe "gst.ingestion.feed-health-changed.v1"
    }

    @Test
    fun `INV-AC-22215 the envelope carries the tenant and the aggregate key of the counter`() {
        val context = publish(FeedControl.MANIFEST, FeedHealthState.FAILED, 1).appended.single().second
        context.tenant.value shouldBe tenant
        context.aggregateKey shouldBe "feed:F1:E-001:2026Q3:MANIFEST"
    }

    @Test
    fun `INV-AC-22215 the event carries no user id or role`() {
        val text =
            publish(FeedControl.SEQUENCE, FeedHealthState.CLEARED, 4)
                .appended
                .single()
                .first
                .single()
                .dataJson
        listOf("actor", "user", "role", "permission").forEach { text.lowercase().contains(it) shouldBe false }
    }
}
