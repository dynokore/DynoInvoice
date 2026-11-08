package com.dynokore.gst.submission.acceptance

import com.dynokore.gst.submission.application.compliance.AlertEmissionLedger
import com.dynokore.gst.submission.application.compliance.AlertValue
import com.dynokore.gst.submission.application.compliance.ComplianceAlertPublication
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantId
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * The event written by `ComplianceAlertPublication` is valid against the pinned JSON Schema of the
 * `ComplianceAlertValuesMeasured` message (INV-T-16885, INV-AC-12837; consumer-driven contract test in the module that
 * stages the contract). A missing `gst.contract.dir` fails the test, it never skips.
 */
class ComplianceAlertPayloadContractTest {
    private val contractDir = File(checkNotNull(System.getProperty("gst.contract.dir")))
    private val asyncapi: Map<String, Any?> = Yaml().load(File(contractDir, "asyncapi.yaml").readText())
    private val mapper = ObjectMapper()
    private val schemas =
        SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
            it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
        }
    private val day = LocalDate.of(2026, 10, 29)

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

    private val everyClaim =
        object : AlertEmissionLedger {
            override fun claim(
                tenant: TenantId,
                entityRef: String,
                periodRef: String,
                measuredOn: LocalDate,
            ) = true
        }

    private fun map(value: Any?): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return value as Map<String, Any?>
    }

    private fun message(): Map<String, Any?> =
        map(map(map(asyncapi["components"])["messages"])[MESSAGE])

    private fun schemaFile(): File =
        File(contractDir, (map(message()["payload"])["\$ref"] as String).removePrefix("./"))

    private fun errors(payload: String): List<String> =
        schemas.getSchema(schemaFile().readText()).validate(mapper.readTree(payload)).map { it.toString() }

    private fun value(
        entity: String,
        without: Int,
        pastDue: Int,
        days: Int?,
    ) = AlertValue(entity, "2026Q1", day, without, pastDue, days)

    private fun publishThree(): List<OutboundEvent> {
        val writer = RecordingWriter()
        ComplianceAlertPublication.publish(
            listOf(value("E-001", 1, 2, 3), value("E-002", 1, 2, null), value("E-003", 4, 0, 5)),
            TenantId(UUID.randomUUID()),
            everyClaim,
            writer,
        )
        return writer.events
    }

    @Test
    fun `INV-AC-12837 the schema of the message resolves to the pinned compliance alert schema file`() {
        schemaFile().relativeTo(contractDir).invariantSeparatorsPath shouldBe
            "schemas/compliance-alert-values-measured-payload.schema.json"
    }

    @Test
    fun `INV-AC-12837 three published events carry the contract type`() {
        val events = publishThree()
        events.size shouldBe 3
        events.forEach {
            it.type shouldBe ComplianceAlertPublication.EVENT_TYPE
            it.type shouldBe message()["name"]
        }
    }

    @Test
    fun `INV-AC-12837 the payload of each published event is valid against the pinned schema`() {
        val events = publishThree()
        events.size shouldBe 3
        events.forEach { errors(it.dataJson).shouldBeEmpty() }
    }

    @Test
    fun `INV-AC-12837 a null days to the nearest deadline is absent from the published payload`() {
        val events = publishThree()
        mapper.readTree(events[1].dataJson).has("daysToNearestDeadline") shouldBe false
        mapper.readTree(events[0].dataJson).has("daysToNearestDeadline") shouldBe true
    }

    @Test
    fun `INV-AC-12837 a payload with no document without submission is refused by the schema`() {
        errors(ComplianceAlertPublication.data(value("E-001", 0, 0, null))).shouldNotBeEmpty()
    }

    private companion object {
        const val MESSAGE = "ComplianceAlertValuesMeasured"
    }
}
