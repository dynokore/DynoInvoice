package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.AggregationDb
import com.dynokore.gst.aggregation.adapters.events.AggregateReadyOutbox
import com.dynokore.gst.aggregation.adapters.http.IntakeBodyCachingFilter
import com.dynokore.gst.aggregation.adapters.http.IntakeController
import com.dynokore.gst.aggregation.adapters.http.NotAggregatableAdvice
import com.dynokore.gst.aggregation.application.HandOnAggregates
import com.dynokore.gst.aggregation.application.IntakeAggregate
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.jobs.JobRuntime
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.serviceruntime.logging.CorrelationId
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.SpecificationVersion
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.File
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The hand-off of an aggregate to submission at the service boundary (INV-REQ-718, channel
 * `aggregationAggregateReady`): the real controller, the TPL-18 use cases, the jOOQ stores over the
 * `aggregation_runtime` login, the outbox, and the schema `aggregate-ready-payload.schema.json` of the contract bundle.
 * Also the higher revision of a recorded source (INV-REQ-434 item 4) over a transmitted aggregate, where the
 * transmitted mark is written directly (its production writer is INV-T-4980).
 */
class AggregateHandOffBoundaryTest : BoundaryFixture() {
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

    @Test
    fun `INV-AC-22175 a stored POS aggregate is handed on as one AggregateReady that validates against the schema`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val receipt = json(boundary.send(tenant, body()).also { it.status shouldBe 202 })
            // the window is still open: nothing is due
            boundary.handOn(tenant).shouldBeEmpty()
            ready(tenant).shouldBeEmpty()
            closeWindows(tenant)
            boundary.handOn(tenant).size shouldBe 1
            boundary.handOn(tenant).shouldBeEmpty()
            val event = ready(tenant).single()
            event["aggregateType"].asString() shouldBe "POS"
            event["aggregateId"].asString() shouldBe receipt["aggregateId"].asString()
            event["linesByInternalTaxCode"].size() shouldBe 1
            event["linesByInternalTaxCode"][0]["internalTaxCode"].asString() shouldBe "SR-STD"
            event["linesByInternalTaxCode"][0]["amount"].asString() shouldBe "1500.00"
            schema.validate(event).map { it.toString() }.shouldBeEmpty()
            // a replay of the intake stores and publishes nothing more
            boundary.send(tenant, body()).status shouldBe 202
            boundary.handOn(tenant).shouldBeEmpty()
            ready(tenant).size shouldBe 1
        }

    @Test
    fun `INV-AC-22175 an intake of a type that is none of POS, STI and PCP publishes no AggregateReady`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            (boundary.send(tenant, body(type = "XYZ")).status in 400..499) shouldBe true
            closeWindows(tenant)
            boundary.handOn(tenant).shouldBeEmpty()
            ready(tenant).shouldBeEmpty()
        }

    @Test
    fun `INV-AC-1746 the event part, a POS aggregate without invoices goes to submission as a Type 2 aggregate event`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            boundary.send(tenant, body()).status shouldBe 202
            closeWindows(tenant)
            boundary.handOn(tenant).size shouldBe 1
            val event = ready(tenant).single()
            event["aggregateType"].asString() shouldBe "POS"
            event["entityRef"].asString() shouldBe "E-001"
            event["linesByInternalTaxCode"].size() shouldBe 1
        }

    @Test
    fun `INV-AC-454 a POS day with SR and ZR sales gives one aggregate with two lines`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val two = "$sr,$zr"
            val receipt = json(boundary.send(tenant, body(lines = two)).also { it.status shouldBe 202 })
            lines(tenant, receipt["aggregateId"].asString()) shouldBe listOf("SR-STD=1500.00", "ZR-EXP=200.50")
            closeWindows(tenant)
            boundary.handOn(tenant).size shouldBe 1
            val event = ready(tenant).single()
            event["linesByInternalTaxCode"].toList().map { it["internalTaxCode"].asString() } shouldBe
                listOf("SR-STD", "ZR-EXP")
            schema.validate(event).map { it.toString() }.shouldBeEmpty()
        }

    @Test
    fun `INV-AC-454 two lines of one code are added into one line`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val same = "$sr,${line("SR-STD", "10.25")}"
            val receipt = json(boundary.send(tenant, body(lines = same)).also { it.status shouldBe 202 })
            lines(tenant, receipt["aggregateId"].asString()) shouldBe listOf("SR-STD=1510.25")
        }

    @Test
    fun `INV-AC-456 INV-REQ-434 late POS lines after transmission give a supplementary aggregate`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val first = json(boundary.send(tenant, body()).also { it.status shouldBe 202 })
            transmit(tenant)
            val before = rowOf(tenant, 1)
            val later = "${line("SR-STD", "1800.00")},${line("ZR-EXP", "50.00")}"
            val sent = boundary.send(tenant, body(sourceRevision = 2, lines = later))
            sent.status shouldBe 202
            val second = json(sent)
            rowOf(tenant, 1) shouldBe before
            (second["documentNumber"].asString() == first["documentNumber"].asString()) shouldBe false
            (second["aggregateId"].asString() == first["aggregateId"].asString()) shouldBe false
            second["sequence"].asLong() shouldBe first["sequence"].asLong() + 1
            query(
                "select supplementary::text from aggregation.aggregate where tenant_id = ? and source_revision = 2",
                tenant,
            ) { it.getString(1) } shouldBe listOf("true")
            lines(tenant, second["aggregateId"].asString()) shouldBe listOf("SR-STD=300.00", "ZR-EXP=50.00")
        }

    @Test
    fun `INV-REQ-718 the window-close job hands on a supplementary aggregate of an entity with no open window`(
        @TempDir dir: Path,
    ) {
        val at = Instant.parse("2027-04-01T16:05:00Z")
        AggregationContext
            .online(dir)
            .withAllowBeanDefinitionOverriding(true)
            .withBean("clock", Clock::class.java, { Clock.fixed(at, ZoneOffset.UTC) })
            .run { context ->
                context.startupFailure?.let { throw it }
                val boundary = Boundary(context)
                val tenant = boundary.tenant()
                boundary.send(tenant, body()).status shouldBe 202
                closeWindows(tenant)
                boundary.handOn(tenant).size shouldBe 1
                transmit(tenant)
                boundary.send(tenant, body(sourceRevision = 2, lines = line("SR-STD", "1800.00"))).status shouldBe 202
                query(
                    "select count(*)::text from aggregation.aggregate_window where tenant_id = ? and state = 'OPEN'",
                    tenant,
                ) { it.getString(1) } shouldBe listOf("0")
                ready(tenant).size shouldBe 1
                // the real job, not the use case: its targets must include the entity that has no open window
                context.getBean(JobRuntime::class.java).fire(WindowCloseJob.NAME, at)
                val events = ready(tenant)
                events.size shouldBe 2
                events.count { event -> event["linesByInternalTaxCode"].toString().contains("300.00") } shouldBe 1
            }
    }
}
