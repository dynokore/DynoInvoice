package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.AggregationDb
import com.dynokore.gst.aggregation.adapters.http.IntakeBodyCachingFilter
import com.dynokore.gst.aggregation.adapters.http.IntakeController
import com.dynokore.gst.aggregation.adapters.http.NotAggregatableAdvice
import com.dynokore.gst.aggregation.application.AggregateRepository
import com.dynokore.gst.aggregation.application.CadenceSource
import com.dynokore.gst.aggregation.application.IntakeAggregate
import com.dynokore.gst.aggregation.application.ShortCodes
import com.dynokore.gst.aggregation.application.WindowRepository
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.platform.outboxandqueue.TenantScope
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
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.util.UUID

/**
 * The field `windowState` of the receipt of `intakeAggregationBatch` against the state stored with the aggregate
 * (INV-Q-13907 A, INV-REQ-434 "A replay returns the original.").
 */
class WindowStateBoundaryTest {
    private val mapper = ObjectMapper()
    private val actor = "user:window-state-test"

    private companion object {
        fun code(ref: String) = if (ref == "E-001") "E" else "B"
    }

    private class Boundary(
        val context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        private val structure: StructureReplica = context.getBean(StructureReplica::class.java)
        val mvc: MockMvc =
            MockMvcBuilders
                .standaloneSetup(IntakeController(context.getBean(IntakeAggregate::class.java)))
                .setControllerAdvice(NotAggregatableAdvice())
                .addFilters<StandaloneMockMvcBuilder>(IntakeBodyCachingFilter())
                .build()

        fun tenant(): UUID =
            UUID.randomUUID().also { tenant ->
                listOf("E-001" to StructureNodeKind.ENTITY, "BU-01" to StructureNodeKind.BUSINESS_UNIT).forEach {
                    structure.accept(
                        OrganisationStructureChanged(
                            TenantId(tenant),
                            it.first,
                            StructureChange.UPDATED,
                            StructureNode(it.second, null, false, it.first, code(it.first), emptyList(), 2),
                        ),
                    )
                }
            }
    }

    private fun run(
        dir: Path,
        block: (Boundary) -> Unit,
    ) {
        AggregationContext.online(dir).run { context ->
            context.startupFailure?.let { throw it }
            block(Boundary(context))
        }
    }

    private fun Boundary.send(
        tenant: UUID,
        sourceKey: String = "K-1",
    ): MockHttpServletResponse =
        scope.bind(TenantId(tenant), actor) {
            CorrelationContext.withCorrelation(CorrelationId("3fa85f64-5717-4562-b3fc-2c963f66afa6")) {
                mvc
                    .perform(
                        post("/internal/v1/aggregation/batches")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .content(
                                """{"entityRef":"E-001","businessUnitRef":"BU-01","aggregateType":"POS",
                                |"periodStart":"2027-03-31","periodEnd":"2027-03-31","underlyingRef":"BATCH-1",
                                |"sourceKey":"$sourceKey","sourceRevision":1,
                                |"linesByInternalTaxCode":[{"internalTaxCode":"SR-STD","amount":"10.00"}]}
                                """.trimMargin(),
                            ),
                    ).andReturn()
                    .response
            }
        }

    private fun state(response: MockHttpServletResponse): String =
        mapper.readTree(response.contentAsString).get("windowState").asString()

    private fun execute(
        tenant: UUID,
        sql: String,
    ) = AggregationDb.superuser().use { connection ->
        connection.prepareStatement(sql).use {
            it.setObject(1, tenant)
            it.executeUpdate()
        }
    }

    private fun openWindowAs(
        tenant: UUID,
        state: String,
    ) = execute(
        tenant,
        "insert into aggregation.aggregate_window (window_id, tenant_id, entity_ref, business_unit_ref, " +
            "aggregate_type, cadence, window_start, window_end, state) select gen_random_uuid(), ?, 'E-001', " +
            "'BU-01', 'POS', 'DAILY', date '2027-03-31', date '2027-03-31', '$state'",
    )

    @Test
    fun `a first intake into an open window answers OPEN and stores it with the aggregate`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            state(boundary.send(tenant)) shouldBe "OPEN"
            AggregationDb.superuser().use { connection ->
                connection
                    .prepareStatement("select window_state from aggregation.aggregate where tenant_id = ?")
                    .use { statement ->
                        statement.setObject(1, tenant)
                        statement.executeQuery().use { rows ->
                            rows.next()
                            rows.getString(1) shouldBe "OPEN"
                        }
                    }
            }
        }

    @Test
    fun `a first intake into a closed window answers CLOSED`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            openWindowAs(tenant, "CLOSED")
            state(boundary.send(tenant)) shouldBe "CLOSED"
        }

    @Test
    fun `a first intake into a late window answers LATE`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            openWindowAs(tenant, "LATE")
            state(boundary.send(tenant)) shouldBe "LATE"
        }

    @Test
    fun `a replay after the window closed answers the stored state, not the current one`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant()
            val first = boundary.send(tenant)
            state(first) shouldBe "OPEN"
            execute(tenant, "update aggregation.aggregate_window set state = 'CLOSED' where tenant_id = ?")
            val replay = boundary.send(tenant)
            state(replay) shouldBe "OPEN"
            replay.contentAsString shouldBe first.contentAsString
        }

    @Test
    fun `the IntakeAggregate bean has windows and cadences both set`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val bean = boundary.context.getBean(IntakeAggregate::class.java)

            fun field(name: String) =
                IntakeAggregate::class.java
                    .getDeclaredField(name)
                    .also { it.isAccessible = true }
                    .get(bean)
            field("windows") shouldNotBe null
            field("cadences") shouldNotBe null
        }

    @Test
    fun `an IntakeAggregate with windows set and cadences null fails to construct`() {
        val shortCodes =
            object : ShortCodes {
                override fun entityCode(entityRef: String): String? = null

                override fun businessUnitCode(businessUnitRef: String): String? = null
            }
        val repository = unusedProxy<AggregateRepository>()
        val windows = unusedProxy<WindowRepository>()
        val cadences = CadenceSource { _, type, _ -> Cadence.defaultFor(type) }
        shouldThrow<IllegalArgumentException> { IntakeAggregate(shortCodes, repository, windows, null) }
        shouldThrow<IllegalArgumentException> { IntakeAggregate(shortCodes, repository, null, cadences) }
    }

    private inline fun <reified T : Any> unusedProxy(): T =
        java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ ->
            error("not called")
        } as T
}
