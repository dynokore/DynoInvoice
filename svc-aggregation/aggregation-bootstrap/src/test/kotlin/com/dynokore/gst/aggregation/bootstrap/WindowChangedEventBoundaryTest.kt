package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.events.WindowChangedOutbox
import com.dynokore.gst.aggregation.application.OpenWindow
import com.dynokore.gst.aggregation.application.WindowChange
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.AggregationWindow
import com.dynokore.gst.aggregation.domain.WindowPart
import com.dynokore.gst.aggregation.domain.WindowState
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.outboxandqueue.OutboundEvent
import com.dynokore.platform.outboxandqueue.OutboxWriter
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * The payload of `AggregateWindowChanged` that [WindowChangedOutbox] appends (channel
 * `aggregationAggregateWindowChanged`):
 * the fields and enum of `aggregate-window-changed-payload.schema.json`, and the catalogue entry of its type.
 */
class WindowChangedEventBoundaryTest {
    private val mapper = ObjectMapper()
    private val tenant = TenantId(UUID.randomUUID())
    private val appended = mutableListOf<Pair<OutboundEvent, EventContext>>()
    private val outbox =
        object : OutboxWriter {
            override fun append(
                events: List<OutboundEvent>,
                context: EventContext,
            ): List<Envelope> {
                events.forEach { appended += it to context }
                return emptyList()
            }
        }
    private val tenants =
        object : TenantContext {
            override fun current(): TenantId = tenant

            override fun currentActor(): String? = null

            override fun <T> bind(
                tenant: TenantId,
                actor: String,
                block: () -> T,
            ): T = block()
        }
    private val publisher = WindowChangedOutbox(outbox, tenants)
    private val window =
        OpenWindow(
            "E-001",
            "BU-01",
            AggregateType.PCP,
            AggregationWindow(LocalDate.of(2022, 3, 28), LocalDate.of(2022, 4, 3)),
        )
    private val part = WindowPart(LocalDate.of(2022, 3, 28), LocalDate.of(2022, 3, 31), "2022-Q1")

    /** Fails closed when the property is missing: the schema is the oracle, read from the contract bundle. */
    private val schema =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
                it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
            }.getSchema(
                File(
                    checkNotNull(System.getProperty("gst.contract.dir")) { "gst.contract.dir is not set" },
                    "schemas/aggregate-window-changed-payload.schema.json",
                ).readText(),
            )

    private fun schemaErrors(body: JsonNode): List<String> = schema.validate(body).map { it.toString() }

    private fun payload(): JsonNode = mapper.readTree(appended.single().first.dataJson)

    @Test
    fun `a CLOSED part is published with the aggregate id and the fields of the schema`() {
        val aggregateId = UUID.randomUUID()
        publisher.changed(WindowChange(window, part, WindowState.CLOSED, aggregateId))
        val body = payload()
        body.propertyNames().toList().sorted() shouldBe
            listOf("aggregateId", "entityRef", "prescribedPeriod", "state", "window")
        body["entityRef"].asString() shouldBe "E-001"
        body["prescribedPeriod"].asString() shouldBe "2022-Q1"
        body["window"]["from"].asString() shouldBe "2022-03-28"
        body["window"]["to"].asString() shouldBe "2022-03-31"
        body["state"].asString() shouldBe "CLOSED"
        body["aggregateId"].asString() shouldBe aggregateId.toString()
        appended.single().second.tenant shouldBe tenant
        schemaErrors(body).shouldBeEmpty()
    }

    @Test
    fun `a LATE part is published without an aggregate id`() {
        publisher.changed(WindowChange(window, part, WindowState.LATE, null))
        payload()["state"].asString() shouldBe "LATE"
        payload().has("aggregateId") shouldBe false
        schemaErrors(payload()).shouldBeEmpty()
    }

    @Test
    fun `a payload without state fails the schema`() {
        publisher.changed(WindowChange(window, part, WindowState.CLOSED, UUID.randomUUID()))
        val without = (payload().deepCopy() as ObjectNode).also { it.remove("state") }
        schemaErrors(without).shouldNotBeEmpty()
    }

    @Test
    fun `the creation of an OPEN window is not a change and is refused`() {
        shouldThrow<IllegalArgumentException> { publisher.changed(WindowChange(window, part, WindowState.OPEN, null)) }
        appended shouldHaveSize 0
    }

    @Test
    fun `a CLOSED part without its aggregate id is refused`() {
        shouldThrow<IllegalArgumentException> {
            publisher.changed(
                WindowChange(window, part, WindowState.CLOSED, null),
            )
        }
    }

    @Test
    fun `the type of the event is a catalogued type of the service`() {
        AggregationEvents.topicByType.keys shouldContain WindowChangedOutbox.TYPE
        AggregationEvents.topicByType[WindowChangedOutbox.TYPE] shouldBe "gst.aggregation.aggregate-window-changed.v1"
        // the emission guard reads the catalogue the service composes
        EventConfiguration().eventCatalogue().find(WindowChangedOutbox.TYPE) shouldNotBe null
    }
}
