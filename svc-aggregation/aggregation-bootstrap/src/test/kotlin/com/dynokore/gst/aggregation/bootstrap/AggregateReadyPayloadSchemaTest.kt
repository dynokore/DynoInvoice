package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.events.AggregateReadyOutbox
import com.dynokore.gst.aggregation.application.NewAggregate
import com.dynokore.gst.aggregation.application.StoredAggregate
import com.dynokore.gst.aggregation.domain.AggregateLine
import com.dynokore.gst.aggregation.domain.AggregateType
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** The `AggregateReady` payload of [AggregateReadyOutbox] against the pinned schema (INV-REQ-437, INV-AC-23681). */
class AggregateReadyPayloadSchemaTest {
    private val mapper = ObjectMapper()
    private val appended = mutableListOf<OutboundEvent>()
    private val outbox =
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
            override fun current(): TenantId = TenantId(UUID.randomUUID())

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }

    /** Fails closed when the property is missing: the schema is the oracle, read from the contract bundle. */
    private val schema =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
                it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
            }.getSchema(
                File(
                    checkNotNull(System.getProperty("gst.contract.dir")) { "gst.contract.dir is not set" },
                    "schemas/aggregate-ready-payload.schema.json",
                ).readText(),
            )

    private fun stored(
        type: AggregateType,
        lines: List<AggregateLine>,
    ) = StoredAggregate(
        NewAggregate(
            entityRef = "E-001",
            businessUnitRef = "BU-01",
            aggregateType = type,
            periodStart = LocalDate.of(2027, 3, 1),
            periodEnd = LocalDate.of(2027, 3, 31),
            sourceKey = "K-1",
            sourceRevision = 1,
            sequence = 1,
            entityCode = "E1",
            businessUnitCode = "B1",
            documentNumber = "AGG-E1-${type.name}-B1-20270331-K-1",
            issueDate = LocalDate.of(2027, 3, 31),
            transactionDate = LocalDate.of(2027, 3, 31),
            lines = lines,
        ),
        UUID.randomUUID(),
    )

    private fun publish(aggregate: StoredAggregate): JsonNode {
        appended.clear()
        AggregateReadyOutbox(outbox, tenants).ready(aggregate)
        return mapper.readTree(appended.single().dataJson)
    }

    @Test
    fun `INV-AC-23681 every event payload validates against the pinned AggregateReady schema`() {
        val withTaxable =
            publish(
                stored(
                    AggregateType.PCP,
                    listOf(AggregateLine("S1", "13.00", "150.00"), AggregateLine("Z1", "0.00", "20.00")),
                ),
            )
        val partial = publish(stored(AggregateType.PCP, listOf(AggregateLine("S1", "13.00", null))))
        val pos = publish(stored(AggregateType.POS, listOf(AggregateLine("S1", "13.00"))))
        listOf(withTaxable, partial, pos).forEach { schema.validate(it).map { e -> e.toString() }.shouldBeEmpty() }
        val lines = withTaxable["linesByInternalTaxCode"]
        lines[0]["taxableAmount"].isString shouldBe true
        lines[0]["taxableAmount"].asString() shouldBe "150.00"
        lines[1]["taxableAmount"].asString() shouldBe "20.00"
        partial["linesByInternalTaxCode"][0].has("taxableAmount") shouldBe false
        pos["linesByInternalTaxCode"][0].has("taxableAmount") shouldBe false
    }
}
