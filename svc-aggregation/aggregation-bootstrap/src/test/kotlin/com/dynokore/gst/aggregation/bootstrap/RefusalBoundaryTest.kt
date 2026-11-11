package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.AggregationDb
import com.dynokore.gst.aggregation.adapters.http.IntakeBodyCachingFilter
import com.dynokore.gst.aggregation.adapters.http.IntakeController
import com.dynokore.gst.aggregation.adapters.http.NotAggregatableAdvice
import com.dynokore.gst.aggregation.adapters.http.TransactionIntakeController
import com.dynokore.gst.aggregation.adapters.persistence.JooqTransactionStore
import com.dynokore.gst.aggregation.application.IntakeAggregate
import com.dynokore.gst.aggregation.application.PeriodBoundarySource
import com.dynokore.gst.aggregation.application.PeriodSpans
import com.dynokore.gst.aggregation.application.WindowRepository
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.gst.aggregation.domain.PeriodSpan
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
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.config.BeanDefinitionCustomizer
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

/**
 * Two refusals at the service boundary over the full context: ING-018 on the batches route (INV-REQ-435 item 3) and
 * ING-002 on the transactions route (INV-REQ-434). The batch use case is the bean `intakeAggregate`, so the
 * `PeriodBoundarySource` bean replaced here is the one it judges with.
 */
class RefusalBoundaryTest {
    private val actor = "user:refusal-test"
    private val q1 = PeriodSpan(LocalDate.of(2022, 1, 1), LocalDate.of(2022, 3, 31), "2022-Q1")
    private val q2 = PeriodSpan(LocalDate.of(2022, 4, 1), LocalDate.of(2022, 6, 30), "2022-Q2")
    private val twoQuarters = PeriodBoundarySource { _, _, _ -> PeriodSpans.Known(listOf(q1, q2)) }
    private val ing018 = Problems.renderIngestion(IngestionErrorCode.CROSSES_PERIOD_BOUNDARY, CORRELATION)
    private val ing002 = Problems.renderIngestion(IngestionErrorCode.SOURCE_REVISION_LOWER, CORRELATION)

    private inner class Boundary(
        context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val tenant: UUID = UUID.randomUUID()
        private val batches: MockMvc = mvcOf(IntakeController(context.getBean(IntakeAggregate::class.java)))
        private val transactionRoute: MockMvc =
            mvcOf(
                TransactionIntakeController(
                    context.getBean(IntakeAggregate::class.java),
                    { _, _, _ -> Cadence.WEEKLY },
                    context.getBean(WindowRepository::class.java),
                    context.getBean(JooqTransactionStore::class.java),
                ),
            )

        private fun mvcOf(controller: Any): MockMvc =
            MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(NotAggregatableAdvice())
                .addFilters<StandaloneMockMvcBuilder>(IntakeBodyCachingFilter())
                .build()

        init {
            val structure = context.getBean(StructureReplica::class.java)
            listOf("E-001" to StructureNodeKind.ENTITY, "BU-01" to StructureNodeKind.BUSINESS_UNIT).forEach {
                structure.accept(
                    OrganisationStructureChanged(
                        TenantId(tenant),
                        it.first,
                        StructureChange.UPDATED,
                        StructureNode(
                            it.second,
                            null,
                            false,
                            it.first,
                            if (it.first ==
                                "E-001"
                            ) {
                                "E"
                            } else {
                                "B"
                            },
                            emptyList(),
                            2,
                        ),
                    ),
                )
            }
        }

        fun post(
            route: String,
            content: String,
        ): MockHttpServletResponse =
            scope.bind(TenantId(tenant), actor) {
                CorrelationContext.withCorrelation(CorrelationId(CORRELATION)) {
                    (if (route == "batches") batches else transactionRoute)
                        .perform(
                            post("/internal/v1/aggregation/$route")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .content(content),
                        ).andReturn()
                        .response
                }
            }

        fun batch(
            start: String,
            end: String,
        ) = post(
            "batches",
            """{"entityRef":"E-001","businessUnitRef":"BU-01","aggregateType":"POS",
            |"periodStart":"$start","periodEnd":"$end","underlyingRef":"BATCH-9001",
            |"sourceKey":"K-$start-$end","sourceRevision":1,
            |"linesByInternalTaxCode":[{"internalTaxCode":"SR-STD","amount":"1500.00"}]}
            """.trimMargin(),
        )

        fun transactions(revision: Int) =
            post(
                "transactions",
                """{"entityRef":"E-001","businessUnitRef":"BU-01","aggregateType":"PCP",
                |"underlyingRef":"BATCH-1","sourceKey":"K-TX","sourceRevision":$revision,
                |"transactions":[{"transactionRef":"T-1-$revision","transactionDate":"2022-03-30",
                |"internalTaxCode":"SR-STD","amount":"10.00"}]}
                """.trimMargin(),
            )

        fun aggregates(): Int =
            AggregationDb.superuser().use { connection ->
                connection.prepareStatement("select count(*) from aggregation.aggregate where tenant_id = ?").use {
                    it.setObject(1, tenant)
                    it.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            }
    }

    private fun run(
        dir: Path,
        spans: PeriodBoundarySource?,
        block: (Boundary) -> Unit,
    ) {
        val runner = AggregationContext.online(dir)
        val configured =
            if (spans == null) {
                runner
            } else {
                // the double is primary: the production bean stays defined and is not the one injected
                runner.withBean(
                    "periodBoundaryDouble",
                    PeriodBoundarySource::class.java,
                    { spans },
                    BeanDefinitionCustomizer { it.isPrimary = true },
                )
            }
        configured.run { context ->
            context.startupFailure?.let { throw it }
            block(Boundary(context))
        }
    }

    @Test
    fun `INV-AC-20109 a pre-aggregated call across the 31 Mar boundary is refused with 422 ING-018 and stores nothing`(
        @TempDir dir: Path,
    ): Unit =
        run(dir, twoQuarters) { boundary ->
            val refused = boundary.batch("2022-03-28", "2022-04-03")
            refused.status shouldBe 422
            refused.contentType shouldBe MediaType.APPLICATION_PROBLEM_JSON_VALUE
            refused.contentAsString shouldBe ing018.body
            boundary.aggregates() shouldBe 0
        }

    @Test
    fun `INV-AC-20109 a call inside one span is answered 202`(
        @TempDir dir: Path,
    ): Unit =
        run(dir, twoQuarters) { boundary ->
            boundary.batch("2022-03-01", "2022-03-31").status shouldBe 202
            boundary.batch("2022-04-04", "2022-04-10").status shouldBe 202
            boundary.aggregates() shouldBe 2
        }

    @Test
    fun `INV-AC-20109 under the production NoPeriodBoundaries bean the crossing call is answered 202`(
        @TempDir dir: Path,
    ): Unit =
        run(dir, null) { boundary ->
            boundary.batch("2022-03-28", "2022-04-03").status shouldBe 202
            boundary.aggregates() shouldBe 1
        }

    @Test
    fun `a lower sourceRevision on the transactions route is refused with 409 ING-002`(
        @TempDir dir: Path,
    ): Unit =
        run(dir, null) { boundary ->
            boundary.transactions(3).status shouldBe 202
            val refused = boundary.transactions(2)
            refused.status shouldBe 409
            refused.contentType shouldBe MediaType.APPLICATION_PROBLEM_JSON_VALUE
            refused.contentAsString shouldBe ing002.body
        }

    @Test
    fun `an exact replay on the transactions route answers 202 with the original receipt`(
        @TempDir dir: Path,
    ): Unit =
        run(dir, null) { boundary ->
            val first = boundary.transactions(3)
            first.status shouldBe 202
            val replay = boundary.transactions(3)
            replay.status shouldBe 202
            replay.contentAsString shouldBe first.contentAsString
        }

    private companion object {
        const val CORRELATION = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    }
}
