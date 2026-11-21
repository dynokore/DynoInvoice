package com.dynokore.gst.submission.acceptance

import com.dynokore.gst.submission.application.readiness.OutboxReadinessSignals
import com.dynokore.gst.submission.application.readiness.ReadinessChangedFacts
import com.dynokore.gst.submission.application.readiness.ReadinessChangedPublication
import com.dynokore.gst.submission.application.readiness.ReadinessSnapshot
import com.dynokore.gst.submission.domain.gate.CheckVerdict
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.util.UUID

/**
 * The data written by `OutboxReadinessSignals` is valid against the pinned JSON Schema of the `ReadinessChanged`
 * message (INV-T-26607, INV-AC-26601). A missing `gst.contract.dir` fails the test, it never skips.
 */
class ReadinessChangedPayloadContractTest {
    private val contractDir = File(checkNotNull(System.getProperty("gst.contract.dir")))
    private val asyncapi: Map<String, Any?> = Yaml().load(File(contractDir, "asyncapi.yaml").readText())
    private val mapper = ObjectMapper()
    private val schemas =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
            it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
        }
    private val tenants =
        object : TenantContext {
            private val id = TenantId(UUID.randomUUID())

            override fun current(): TenantId = id

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    private class RecordingWriter : OutboxWriter {
        val events = mutableListOf<OutboundEvent>()

        override fun append(
            events: List<OutboundEvent>,
            context: EventContext,
        ): List<Envelope> {
            this.events += events
            return emptyList()
        }
    }

    private fun map(value: Any?): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private fun message(): Map<String, Any?> = map(map(map(asyncapi["components"])["messages"])[MESSAGE])

    private fun schemaFile(): File =
        File(contractDir, (map(message()["payload"])["\$ref"] as String).removePrefix("./"))

    private fun errors(payload: String): List<String> =
        schemas.getSchema(schemaFile().readText()).validate(mapper.readTree(payload)).map { it.toString() }

    private val snapshot =
        ReadinessSnapshot(
            CheckVerdict.NOT_READY,
            2,
            mapOf(LifecycleState.BLOCKED to 1, LifecycleState.QUEUED to 1),
        )

    private fun staged(): OutboundEvent {
        val writer = RecordingWriter()
        OutboxReadinessSignals(writer, tenants).stage(ReadinessChangedFacts("E-001", "2026Q1", snapshot, "chk-7"))
        return writer.events.single()
    }

    @Test
    fun `INV-AC-26601 the schema of the message resolves to the pinned readiness changed schema file`() {
        schemaFile().relativeTo(contractDir).invariantSeparatorsPath shouldBe
            "schemas/readiness-changed-payload.schema.json"
        ReadinessChangedPublication.EVENT_TYPE shouldBe message()["name"]
    }

    @Test
    fun `INV-AC-26601 the staged data is valid against the pinned schema and carries the snapshot blocked count`() {
        val event = staged()
        errors(event.dataJson).shouldBeEmpty()
        mapper.readTree(event.dataJson).get("blockedCount").asInt() shouldBe snapshot.blockedCount
    }

    @Test
    fun `INV-AC-26601 a quote and a backslash in the entity reference parse back to the same value`() {
        val entity = "E\"1\\x"
        val data = ReadinessChangedPublication.data(ReadinessChangedFacts(entity, "2026Q1", snapshot, "chk-7"))
        mapper.readTree(data).get("entityRef").asString() shouldBe entity
    }

    private companion object {
        const val MESSAGE = "ReadinessChanged"
    }
}
