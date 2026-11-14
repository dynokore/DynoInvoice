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

/** Shared fixture of the aggregation boundary tests: the real controller over the online context. */
abstract class BoundaryFixture {
    protected val mapper = ObjectMapper()
    protected val actor = "user:handoff-test"

    protected class Boundary(
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

    protected fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        AggregationContext.online(dir).run { context -> block(Boundary(context)) }
    }

    protected fun body(
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

    protected fun Boundary.send(
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

    protected fun Boundary.handOn(tenant: UUID) =
        scope.bind(TenantId(tenant), actor) {
            scope.transactions.inTransaction { handOn.execute("E-001") }
        }

    protected fun json(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.contentAsString)

    protected fun <T> query(
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

    protected fun update(sql: String) =
        AggregationDb.superuser().use { connection -> connection.createStatement().use { it.executeUpdate(sql) } }

    /** The window of the intake is reached by the closing run: its state leaves OPEN. */
    protected fun closeWindows(tenant: UUID) =
        update("update aggregation.aggregate_window set state = 'CLOSED' where tenant_id = '$tenant'")

    protected fun transmit(tenant: UUID) =
        update(
            "update aggregation.aggregate set transmitted_at = now() where tenant_id = '$tenant' " +
                "and superseded_by is null and transmitted_at is null",
        )

    protected fun ready(tenant: UUID): List<JsonNode> =
        query(
            "select data from aggregation.outbox where tenant_id = ? " +
                "and type = '${AggregateReadyOutbox.TYPE}' order by 1",
            tenant,
        ) { mapper.readTree(it.getString(1)) }

    protected fun rowOf(
        tenant: UUID,
        revision: Int,
    ): String =
        query(
            "select row_to_json(a)::text from aggregation.aggregate a " +
                "where tenant_id = ? and source_revision = $revision",
            tenant,
        ) { it.getString(1) }.single()

    protected fun count(tenant: UUID) =
        query("select count(*)::text from aggregation.aggregate where tenant_id = ?", tenant) { it.getString(1) }

    protected fun line(
        code: String,
        amount: String,
    ) = """{"internalTaxCode":"$code","amount":"$amount"}"""

    protected val sr = line("SR-STD", "1500.00")
    protected val zr = line("ZR-EXP", "200.50")

    protected fun lines(
        tenant: UUID,
        aggregateId: String,
    ): List<String> =
        query(
            "select internal_tax_code || '=' || amount from aggregation.aggregate_line " +
                "where tenant_id = ? and aggregate_id = '$aggregateId' order by position",
            tenant,
        ) { it.getString(1) }

    protected companion object {
        const val CORRELATION = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    }
}
