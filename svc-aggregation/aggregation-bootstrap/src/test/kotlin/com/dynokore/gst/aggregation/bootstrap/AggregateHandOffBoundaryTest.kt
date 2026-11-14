package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.AggregationDb
import com.dynokore.gst.aggregation.adapters.events.AggregateReadyOutbox
import com.dynokore.gst.aggregation.adapters.http.IntakeBodyCachingFilter
import com.dynokore.gst.aggregation.adapters.http.IntakeController
import com.dynokore.gst.aggregation.adapters.http.NotAggregatableAdvice
import com.dynokore.gst.aggregation.application.HandOnAggregates
import com.dynokore.gst.aggregation.application.IntakeAggregate
import com.dynokore.platform.outboxandqueue.TenantScope
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The hand-off of an aggregate to submission at the service boundary (INV-REQ-718, channel
 * `aggregationAggregateReady`): the real controller, the TPL-18 use cases, the jOOQ stores over the
 * `aggregation_runtime` login, the outbox, and the schema `aggregate-ready-payload.schema.json` of the contract bundle.
 * Also the higher revision of a recorded source (INV-REQ-434 item 4) over a transmitted aggregate, where the
 * transmitted mark is written directly (its production writer is INV-T-4980).
 */
class AggregateHandOffBoundaryTest {
    private val mapper = ObjectMapper()
    private val actor = "user:handoff-test"

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

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        private val structure: StructureReplica = context.getBean(StructureReplica::class.java)
        val handOn: HandOnAggregates = context.getBean(HandOnAggregates::class.java)
        val mvc: MockMvc =
            MockMvcBuilders
                .standaloneSetup(IntakeController(context.getBean(IntakeAggregate::class.java)))
                .setControllerAdvice(NotAggregatableAdvice())
                .addFilters<StandaloneMockMvcBuilder>(IntakeBodyCachingFilter())
                .build()
        private val versions = AtomicLong(1)

        fun node(
            tenant: UUID,
            ref: String,
            kind: StructureNodeKind,
            code: String?,
        ) = structure.accept(
            OrganisationStructureChanged(
                TenantId(tenant),
                ref,
                StructureChange.UPDATED,
                StructureNode(kind, null, false, ref, code, emptyList(), versions.incrementAndGet()),
            ),
        )

        fun tenant(): UUID =
            UUID.randomUUID().also {
                node(it, "E-001", StructureNodeKind.ENTITY, "E")
                node(it, "E-002", StructureNodeKind.ENTITY, "F")
                node(it, "BU-01", StructureNodeKind.BUSINESS_UNIT, "B")
            }
    }

    private fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        AggregationContext.online(dir).run { context -> block(Boundary(context)) }
    }

    private fun body(
        sourceKey: String = "K-1",
        sourceRevision: Int = 1,
        entityRef: String = "E-001",
        type: String = "POS",
        lines: String = """{"internalTaxCode":"SR-STD","amount":"1500.00"}""",
    ) = """{"entityRef":"$entityRef","businessUnitRef":"BU-01","aggregateType":"$type",
        |"periodStart":"2027-03-01","periodEnd":"2027-03-31","underlyingRef":"BATCH-9001",
        |"sourceKey":"$sourceKey","sourceRevision":$sourceRevision,
        |"linesByInternalTaxCode":[$lines]}
        """.trimMargin()

    private fun Boundary.send(
        tenant: UUID,
        content: String,
    ): MockHttpServletResponse =
        scope.bind(TenantId(tenant), actor) {
            CorrelationContext.withCorrelation(CorrelationId(CORRELATION)) {
                mvc
                    .perform(
                        post("/internal/v1/aggregation/batches")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .content(content),
                    ).andReturn()
                    .response
            }
        }

    private fun Boundary.handOn(tenant: UUID) =
        scope.bind(TenantId(tenant), actor) {
            scope.transactions.inTransaction { handOn.execute("E-001") }
        }

    private fun json(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.contentAsString)

    private fun <T> query(
        sql: String,
        tenant: UUID,
        read: (java.sql.ResultSet) -> T,
    ): List<T> =
        AggregationDb.superuser().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, tenant)
                statement.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) read(rows) else null }.toList()
                }
            }
        }

    private fun update(sql: String) =
        AggregationDb.superuser().use { connection -> connection.createStatement().use { it.executeUpdate(sql) } }

    /** The window of the intake is reached by the closing run: its state leaves OPEN. */
    private fun closeWindows(tenant: UUID) =
        update("update aggregation.aggregate_window set state = 'CLOSED' where tenant_id = '$tenant'")

    private fun transmit(tenant: UUID) =
        update(
            "update aggregation.aggregate set transmitted_at = now() where tenant_id = '$tenant' " +
                "and superseded_by is null and transmitted_at is null",
        )

    private fun ready(tenant: UUID): List<JsonNode> =
        query(
            "select data from aggregation.outbox where tenant_id = ? " +
                "and type = '${AggregateReadyOutbox.TYPE}' order by 1",
            tenant,
        ) { mapper.readTree(it.getString(1)) }

    private fun rowOf(
        tenant: UUID,
        revision: Int,
    ): String =
        query(
            "select row_to_json(a)::text from aggregation.aggregate a " +
                "where tenant_id = ? and source_revision = $revision",
            tenant,
        ) { it.getString(1) }.single()

    private fun count(tenant: UUID) =
        query("select count(*)::text from aggregation.aggregate where tenant_id = ?", tenant) { it.getString(1) }

    private fun line(
        code: String,
        amount: String,
    ) = """{"internalTaxCode":"$code","amount":"$amount"}"""

    private val sr = line("SR-STD", "1500.00")
    private val zr = line("ZR-EXP", "200.50")

    private fun lines(
        tenant: UUID,
        aggregateId: String,
    ): List<String> =
        query(
            "select internal_tax_code || '=' || amount from aggregation.aggregate_line " +
                "where tenant_id = ? and aggregate_id = '$aggregateId' order by position",
            tenant,
        ) { it.getString(1) }

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
    fun `INV-REQ-434 revision 2 over an untransmitted revision 1 supersedes it with the same number`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val one = json(boundary.send(tenant, body()).also { it.status shouldBe 202 })
            val changed = """{"internalTaxCode":"SR-STD","amount":"1700.00"}"""
            val sent = boundary.send(tenant, body(sourceRevision = 2, lines = changed))
            sent.status shouldBe 202
            val two = json(sent)
            two["documentNumber"].asString() shouldBe one["documentNumber"].asString()
            two["sequence"].asLong() shouldBe one["sequence"].asLong()
            query(
                "select superseded_by::text from aggregation.aggregate where tenant_id = ? and source_revision = 1",
                tenant,
            ) { it.getString(1) } shouldBe listOf(two["aggregateId"].asString())
            lines(tenant, two["aggregateId"].asString()) shouldBe listOf("SR-STD=1700.00")
            lines(tenant, one["aggregateId"].asString()) shouldBe listOf("SR-STD=1500.00")
            // a replay of revision 2 answers the stored receipt
            val replay = json(boundary.send(tenant, body(sourceRevision = 2, lines = changed)))
            replay["aggregateId"].asString() shouldBe two["aggregateId"].asString()
            replay["documentNumber"].asString() shouldBe two["documentNumber"].asString()
        }

    @Test
    fun `INV-REQ-434 revision 2 over a transmitted revision 1 leaves it unchanged and adds a supplementary aggregate`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val one = json(boundary.send(tenant, body()).also { it.status shouldBe 202 })
            transmit(tenant)
            val before = rowOf(tenant, 1)
            val two =
                json(
                    boundary
                        .send(
                            tenant,
                            body(sourceRevision = 2, lines = """{"internalTaxCode":"SR-STD","amount":"1600.00"}"""),
                        ).also { it.status shouldBe 202 },
                )
            rowOf(tenant, 1) shouldBe before
            two["sequence"].asLong() shouldBe one["sequence"].asLong() + 1
            lines(tenant, two["aggregateId"].asString()) shouldBe listOf("SR-STD=100.00")
            // a transmitted supplementary is not amended either: revision 3 is compared with everything transmitted
            transmit(tenant)
            val three =
                json(
                    boundary
                        .send(
                            tenant,
                            body(sourceRevision = 3, lines = """{"internalTaxCode":"SR-STD","amount":"1650.00"}"""),
                        ).also { it.status shouldBe 202 },
                )
            two["sequence"].asLong() + 1 shouldBe three["sequence"].asLong()
            lines(tenant, three["aggregateId"].asString()) shouldBe listOf("SR-STD=50.00")
        }

    @Test
    fun `INV-REQ-434 revision 3 after a superseded revision 2 follows the same rule against the current aggregate`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val one = json(boundary.send(tenant, body()).also { it.status shouldBe 202 })
            boundary.send(tenant, body(sourceRevision = 2)).status shouldBe 202
            val three =
                json(
                    boundary
                        .send(
                            tenant,
                            body(sourceRevision = 3, lines = """{"internalTaxCode":"SR-STD","amount":"900.00"}"""),
                        ).also { it.status shouldBe 202 },
                )
            three["documentNumber"].asString() shouldBe one["documentNumber"].asString()
            query(
                "select source_revision::text from aggregation.aggregate where tenant_id = ? and superseded_by is null",
                tenant,
            ) { it.getString(1) } shouldBe listOf("3")
            count(tenant) shouldBe listOf("3")
        }

    @Test
    fun `INV-REQ-434 another tenant's or another entity's aggregate with the same sourceKey is never touched`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val other = boundary.tenant()
            boundary.send(tenant, body(entityRef = "E-002")).status shouldBe 202
            boundary.send(other, body()).status shouldBe 202
            transmit(other)
            val entityBefore = rowOf(tenant, 1)
            val otherBefore = rowOf(other, 1)
            boundary.send(tenant, body()).status shouldBe 202
            boundary.send(tenant, body(sourceRevision = 2)).status shouldBe 202
            query(
                "select row_to_json(a)::text from aggregation.aggregate a where tenant_id = ? and entity_ref = 'E-002'",
                tenant,
            ) { it.getString(1) } shouldBe listOf(entityBefore)
            rowOf(other, 1) shouldBe otherBefore
            count(other) shouldBe listOf("1")
        }

    private companion object {
        const val CORRELATION = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    }
}
