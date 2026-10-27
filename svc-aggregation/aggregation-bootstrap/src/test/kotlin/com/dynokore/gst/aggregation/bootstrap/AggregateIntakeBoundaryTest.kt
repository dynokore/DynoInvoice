package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.AggregationDb
import com.dynokore.gst.aggregation.adapters.http.IntakeBodyCachingFilter
import com.dynokore.gst.aggregation.adapters.http.IntakeController
import com.dynokore.gst.aggregation.adapters.http.NotAggregatableAdvice
import com.dynokore.gst.aggregation.application.IntakeAggregate
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.http.IngestionErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.serviceruntime.logging.CorrelationId
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import io.kotest.assertions.throwables.shouldThrow
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
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * The aggregate intake at the service boundary (P109): the real controller, the TPL-18 use case, the jOOQ store
 * over the `aggregation_runtime` login and the structure replica; the tenant is bound as the runtime binds it from
 * the token.
 * INV-REQ-427, INV-REQ-434 items 1, 2, 3 and 5, INV-REQ-436.
 */
class AggregateIntakeBoundaryTest {
    private val mapper = ObjectMapper()
    private val actor = "user:intake-test"

    private class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val structure: StructureReplica = context.getBean(StructureReplica::class.java)
        val mvc: MockMvc =
            MockMvcBuilders
                .standaloneSetup(IntakeController(context.getBean(IntakeAggregate::class.java)))
                .setControllerAdvice(NotAggregatableAdvice())
                .addFilters<StandaloneMockMvcBuilder>(IntakeBodyCachingFilter())
                .build()
        private val versions =
            java.util.concurrent.atomic
                .AtomicLong(1)

        fun node(
            tenant: UUID,
            ref: String,
            kind: StructureNodeKind,
            code: String?,
            retired: Boolean = false,
        ) = structure.accept(
            OrganisationStructureChanged(
                TenantId(tenant),
                ref,
                StructureChange.UPDATED,
                StructureNode(kind, null, retired, ref, code, emptyList(), versions.incrementAndGet()),
            ),
        )

        /** A tenant whose entity E-001 has code `E` and whose business unit BU-01 has code `B`. */
        fun tenant(
            entityCode: String? = "E",
            unitCode: String? = "B",
        ): UUID =
            UUID.randomUUID().also {
                node(it, "E-001", StructureNodeKind.ENTITY, entityCode)
                node(it, "BU-01", StructureNodeKind.BUSINESS_UNIT, unitCode)
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
        periodEnd: String = "2027-03-31",
    ) = """{"entityRef":"$entityRef","businessUnitRef":"BU-01","aggregateType":"$type",
        |"periodStart":"2027-03-01","periodEnd":"$periodEnd","underlyingRef":"BATCH-9001",
        |"sourceKey":"$sourceKey","sourceRevision":$sourceRevision,
        |"linesByInternalTaxCode":[{"internalTaxCode":"SR-STD","amount":"1500.00"}]}
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

    private fun json(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.contentAsString)

    private fun aggregates(tenant: UUID): Int =
        AggregationDb.superuser().use { connection ->
            connection.prepareStatement("select count(*) from aggregation.aggregate where tenant_id = ?").use {
                it.setObject(1, tenant)
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    /** `last_value` of the numbering sequence; null before its first use. */
    private fun sequenceValue(): Long? =
        AggregationDb.superuser().use { connection ->
            connection.createStatement().use {
                it
                    .executeQuery(
                        "select last_value from pg_sequences " +
                            "where schemaname = 'aggregation' and sequencename = 'aggregate_sequence'",
                    ).use { rows ->
                        rows.next()
                        rows.getObject(1) as Long?
                    }
            }
        }

    private val ing002 = Problems.renderIngestion(IngestionErrorCode.SOURCE_REVISION_LOWER, CORRELATION)
    private val ing022 = Problems.renderIngestion(IngestionErrorCode.AGGREGATE_NUMBER_NOT_BUILDABLE, CORRELATION)

    @Test
    fun `INV-AC-453 a first intake stores one aggregate and answers 202 with its receipt`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val response = boundary.send(tenant, body())
            response.status shouldBe 202
            json(response).get("entityRef").asString() shouldBe "E-001"
            json(response).get("sequence").asLong() shouldBe
                json(response)
                    .get("documentNumber")
                    .asString()
                    .substringAfterLast('-')
                    .toLong()
            aggregates(tenant) shouldBe 1
        }

    @Test
    fun `INV-AC-453 an exact replay answers the original, creates nothing and keeps the sequence`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val first = boundary.send(tenant, body())
            val before = sequenceValue()
            val replay = boundary.send(tenant, body())
            replay.status shouldBe 202
            replay.contentAsString shouldBe first.contentAsString
            aggregates(tenant) shouldBe 1
            sequenceValue() shouldBe before
        }

    @Test
    fun `INV-AC-453 a replay is answered whatever the structure replica holds now`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val first = boundary.send(tenant, body())
            boundary.node(tenant, "E-001", StructureNodeKind.ENTITY, null, retired = true)
            boundary.send(tenant, body()).contentAsString shouldBe first.contentAsString
            aggregates(tenant) shouldBe 1
        }

    @Test
    fun `INV-AC-453 a lower sourceRevision is refused with 409 ING-002 and nothing is stored`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            boundary.send(tenant, body(sourceRevision = 3)).status shouldBe 202
            val before = sequenceValue()
            val refused = boundary.send(tenant, body(sourceRevision = 2))
            refused.status shouldBe 409
            refused.contentAsString shouldBe ing002.body
            json(refused).get("code").asString() shouldBe "ING-002"
            aggregates(tenant) shouldBe 1
            sequenceValue() shouldBe before
        }

    @Test
    fun `INV-AC-453 a higher revision and another sourceKey are new aggregates`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val numbers =
                listOf(body(), body(sourceRevision = 2), body(sourceKey = "K-2")).map {
                    val response = boundary.send(tenant, it)
                    response.status shouldBe 202
                    json(response).get("documentNumber").asString()
                }
            numbers.toSet().size shouldBe 3
            aggregates(tenant) shouldBe 3
        }

    @Test
    fun `INV-AC-453 the same sourceKey under two entities is two aggregates`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            boundary.node(tenant, "E-002", StructureNodeKind.ENTITY, "F")
            val one = boundary.send(tenant, body())
            val two = boundary.send(tenant, body(entityRef = "E-002"))
            listOf(one, two).forEach { it.status shouldBe 202 }
            (one.contentAsString == two.contentAsString) shouldBe false
            aggregates(tenant) shouldBe 2
        }

    @Test
    fun `INV-AC-453 two tenants with the same triple each get their own aggregate and see only it`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val a = boundary.tenant()
            val b = boundary.tenant()
            val one = boundary.send(a, body())
            val two = boundary.send(b, body())
            listOf(one, two).forEach { it.status shouldBe 202 }
            val idOfA = json(one).get("aggregateId").asString()
            json(boundary.send(a, body())).get("aggregateId").asString() shouldBe idOfA
            (idOfA == json(two).get("aggregateId").asString()) shouldBe false
            aggregates(a) shouldBe 1
            aggregates(b) shouldBe 1
        }

    @Test
    fun `INV-AC-453 two concurrent identical intakes leave one aggregate and both answer 202`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val pool = Executors.newFixedThreadPool(2)
            try {
                repeat(5) { round ->
                    val start = CountDownLatch(1)
                    val content = body(sourceKey = "RACE-$round")
                    val calls =
                        List(2) {
                            pool.submit<MockHttpServletResponse> {
                                start.await()
                                boundary.send(tenant, content)
                            }
                        }
                    start.countDown()
                    val responses = calls.map { it.get() }
                    responses.forEach { it.status shouldBe 202 }
                    responses[0].contentAsString shouldBe responses[1].contentAsString
                }
            } finally {
                pool.shutdownNow()
            }
            aggregates(tenant) shouldBe 5
        }

    @Test
    fun `INV-AC-453 a clash on the document number is not taken for a replay`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val first = json(boundary.send(tenant, body()))
            AggregationDb.superuser().use { connection ->
                connection.createStatement().use {
                    it.execute("select setval('aggregation.aggregate_sequence', ${first.get("sequence").asLong() - 1})")
                }
            }
            // the next sequence value repeats the number of the first aggregate: the document_number key refuses it
            val failure = shouldThrow<Exception> { boundary.send(tenant, body(sourceKey = "K-OTHER")) }
            generateSequence<Throwable>(failure) { it.cause }
                .any { it.message?.contains("aggregate_document_number_key") == true } shouldBe true
            aggregates(tenant) shouldBe 1
        }

    @Test
    fun `INV-AC-1436 a POS aggregate is numbered AGG-E-POS-B-period end-seq and dated at its period end`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val response = boundary.send(tenant, body())
            response.status shouldBe 202
            val receipt = json(response)
            val sequence = receipt.get("sequence").asLong()
            receipt.get("documentNumber").asString() shouldBe "AGG-E-POS-B-20270331-$sequence"
            receipt.get("issueDate").asString() shouldBe "2027-03-31"
            receipt.get("transactionDate").asString() shouldBe "2027-03-31"
        }

    @Test
    fun `INV-AC-1436 the number carries the short codes, never the references`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant(entityCode = "ACME1", unitCode = "RETAIL")
            val number = json(boundary.send(tenant, body(type = "STI"))).get("documentNumber").asString()
            number.startsWith("AGG-ACME1-STI-RETAIL-20270331-") shouldBe true
        }

    @Test
    fun `INV-AC-1436 a missing or unusable short code is refused with 422 ING-022 and nothing is stored`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val cases =
                listOf(
                    boundary.tenant(entityCode = null),
                    boundary.tenant(unitCode = null),
                    boundary.tenant(entityCode = "a1"),
                    boundary.tenant(unitCode = "ELEVENCHARS"),
                    boundary.tenant(unitCode = ""),
                    UUID.randomUUID(),
                ).toMutableList()
            val retired = boundary.tenant()
            boundary.node(retired, "E-001", StructureNodeKind.ENTITY, "E", retired = true)
            cases += retired
            cases += boundary.tenant().also { boundary.node(it, "BU-01", StructureNodeKind.ENTITY, "B") }
            val before = sequenceValue()
            cases.forEach { tenant ->
                val response = boundary.send(tenant, body())
                response.status shouldBe 422
                response.contentAsString shouldBe ing022.body
                aggregates(tenant) shouldBe 0
            }
            sequenceValue() shouldBe before
        }

    @Test
    fun `INV-AC-2779 a replay after the replica code changed answers the original number`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val first = boundary.send(tenant, body())
            boundary.node(tenant, "E-001", StructureNodeKind.ENTITY, "NEWE")
            boundary.node(tenant, "BU-01", StructureNodeKind.BUSINESS_UNIT, "NEWB")
            val replay = boundary.send(tenant, body())
            replay.status shouldBe 202
            replay.contentAsString shouldBe first.contentAsString
            json(replay).get("documentNumber").asString().contains("NEW") shouldBe false
            AggregationDb.superuser().use { connection ->
                connection
                    .prepareStatement(
                        "select entity_code, business_unit_code from aggregation.aggregate where tenant_id = ?",
                    ).use { statement ->
                        statement.setObject(1, tenant)
                        statement.executeQuery().use { rows ->
                            generateSequence { if (rows.next()) rows.getString(1) to rows.getString(2) else null }
                                .toList() shouldBe listOf("E" to "B")
                        }
                    }
            }
        }

    private companion object {
        const val CORRELATION = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    }
}
