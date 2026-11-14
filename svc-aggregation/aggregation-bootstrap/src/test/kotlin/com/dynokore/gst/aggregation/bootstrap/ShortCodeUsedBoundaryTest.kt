package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.AggregationDb
import com.dynokore.gst.aggregation.adapters.events.ShortCodeUsedOutbox
import com.dynokore.gst.aggregation.adapters.http.IntakeBodyCachingFilter
import com.dynokore.gst.aggregation.adapters.http.IntakeController
import com.dynokore.gst.aggregation.adapters.http.NotAggregatableAdvice
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
 * `ShortCodeUsed` at the service boundary (INV-AC-2779, INV-Q-11266 A): the real controller, use case, store and outbox
 * writer over the Testcontainers database. An intake answered 202 leaves the outbox rows, validated by the staged
 * contract schema; a refused intake (422 ING-022, 409 ING-002) leaves none.
 */
class ShortCodeUsedBoundaryTest {
    private val mapper = ObjectMapper()
    private val actor = "user:short-code-used-test"
    private val versions = AtomicLong(1)

    private val schema =
        SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12) {
                it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
            }.getSchema(
                File(
                    checkNotNull(System.getProperty("gst.contract.dir")) { "gst.contract.dir is not set" },
                    "schemas/short-code-used-payload.schema.json",
                ).readText(),
            )

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
    }

    private fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        AggregationContext.online(dir).run { context -> block(Boundary(context)) }
    }

    private fun Boundary.tenant(
        entityCode: String? = "E1",
        unitCode: String? = "B1",
    ): UUID =
        UUID.randomUUID().also { tenant ->
            listOf(
                Triple("E-001", StructureNodeKind.ENTITY, entityCode),
                Triple("BU-01", StructureNodeKind.BUSINESS_UNIT, unitCode),
            ).forEach { (ref, kind, code) ->
                structure.accept(
                    OrganisationStructureChanged(
                        TenantId(tenant),
                        ref,
                        StructureChange.UPDATED,
                        StructureNode(kind, null, false, ref, code, emptyList(), versions.incrementAndGet()),
                    ),
                )
            }
        }

    private fun body(sourceRevision: Int = 1) =
        """{"entityRef":"E-001","businessUnitRef":"BU-01","aggregateType":"POS",
        |"periodStart":"2027-03-01","periodEnd":"2027-03-31","underlyingRef":"BATCH-9001",
        |"sourceKey":"K-1","sourceRevision":$sourceRevision,
        |"linesByInternalTaxCode":[{"internalTaxCode":"SR-STD","amount":"1500.00"}]}
        """.trimMargin()

    private fun Boundary.send(
        tenant: UUID,
        content: String,
    ): MockHttpServletResponse =
        scope.bind(TenantId(tenant), actor) {
            CorrelationContext.withCorrelation(CorrelationId("55555555-5555-4555-8555-555555555555")) {
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

    private fun payloads(tenant: UUID): List<JsonNode> =
        AggregationDb.superuser().use { connection ->
            connection
                .prepareStatement(
                    "select data::text from aggregation.outbox where tenant_id = ? and type = ? order by seq",
                ).use {
                    it.setObject(1, tenant)
                    it.setString(2, ShortCodeUsedOutbox.TYPE)
                    it.executeQuery().use { rows ->
                        generateSequence { if (rows.next()) mapper.readTree(rows.getString(1)) else null }.toList()
                    }
                }
        }

    private fun usedCodes(tenant: UUID): Int =
        AggregationDb.superuser().use { connection ->
            connection.prepareStatement("select count(*) from aggregation.used_short_code where tenant_id = ?").use {
                it.setObject(1, tenant)
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    @Test
    fun `INV-AC-2779 an intake answered 202 leaves one ShortCodeUsed row per code that validates against the schema`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val response = boundary.send(tenant, body())
            response.status shouldBe 202
            val receipt = mapper.readTree(response.contentAsString)
            val rows = payloads(tenant)
            rows.map { it.get("code").asString() } shouldBe listOf("E1", "B1")
            rows.map { it.get("nodeKind").asString() } shouldBe listOf("ENTITY", "BUSINESS_UNIT")
            rows.map { it.get("nodeRef").asString() } shouldBe listOf("E-001", "BU-01")
            rows.forEach {
                it.get("documentNumber").asString() shouldBe receipt.get("documentNumber").asString()
                schema.validate(it).map { error -> error.toString() }.shouldBeEmpty()
            }
            usedCodes(tenant) shouldBe 2
            // an exact replay answers 202 and adds nothing
            boundary.send(tenant, body()).status shouldBe 202
            payloads(tenant).size shouldBe 2
        }

    @Test
    fun `INV-AC-2779 an intake refused with 422 ING-022 leaves no used code row and no outbox row`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant(unitCode = null)
            boundary.send(tenant, body()).status shouldBe 422
            payloads(tenant).shouldBeEmpty()
            usedCodes(tenant) shouldBe 0
        }

    @Test
    fun `INV-AC-2779 an intake refused with 409 ING-002 emits nothing beyond the first`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            boundary.send(tenant, body(sourceRevision = 3)).status shouldBe 202
            payloads(tenant).size shouldBe 2
            val refused = boundary.send(tenant, body(sourceRevision = 2))
            refused.status shouldBe 409
            mapper.readTree(refused.contentAsString).get("code").asString() shouldBe "ING-002"
            payloads(tenant).size shouldBe 2
            usedCodes(tenant) shouldBe 2
        }
}
