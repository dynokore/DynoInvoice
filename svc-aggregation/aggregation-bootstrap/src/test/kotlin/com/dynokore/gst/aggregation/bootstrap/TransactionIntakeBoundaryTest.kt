package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.AggregationDb
import com.dynokore.gst.aggregation.adapters.http.IntakeBodyCachingFilter
import com.dynokore.gst.aggregation.adapters.http.NotAggregatableAdvice
import com.dynokore.gst.aggregation.adapters.http.TransactionIntakeController
import com.dynokore.gst.aggregation.adapters.periods.NoPeriodBoundaries
import com.dynokore.gst.aggregation.adapters.persistence.JooqTransactionStore
import com.dynokore.gst.aggregation.application.AggregateRepository
import com.dynokore.gst.aggregation.application.CadenceSource
import com.dynokore.gst.aggregation.application.CloseResult
import com.dynokore.gst.aggregation.application.CloseWindows
import com.dynokore.gst.aggregation.application.IntakeAggregate
import com.dynokore.gst.aggregation.application.PeriodBoundarySource
import com.dynokore.gst.aggregation.application.PeriodSpans
import com.dynokore.gst.aggregation.application.ShortCodes
import com.dynokore.gst.aggregation.application.WindowRepository
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.gst.aggregation.domain.PeriodSpan
import com.dynokore.platform.outboxandqueue.TenantScope
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
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.time.LocalDate
import java.util.UUID

/**
 * The dated-transaction intake and the split of a closed window at the prescribed-period boundaries (INV-REQ-435
 * items 1 and 2, INV-REQ-434), over the composed service and the database. The cadence and the period spans are test
 * doubles of the two ports; the production source of spans is [NoPeriodBoundaries] until INV-T-16565.
 */
class TransactionIntakeBoundaryTest {
    private val mapper = ObjectMapper()
    private val actor = "user:transaction-intake-test"
    private val q1 = PeriodSpan(LocalDate.of(2022, 1, 1), LocalDate.of(2022, 3, 31), "2022-Q1")
    private val q2 = PeriodSpan(LocalDate.of(2022, 4, 1), LocalDate.of(2022, 6, 30), "2022-Q2")

    private inner class Boundary(
        val context: ApplicationContext,
        cadence: Cadence,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val tenant: UUID = UUID.randomUUID()
        val windows: WindowRepository = context.getBean(WindowRepository::class.java)
        private val intake =
            IntakeAggregate(context.getBean(ShortCodes::class.java), context.getBean(AggregateRepository::class.java))
        val mvc: MockMvc =
            MockMvcBuilders
                .standaloneSetup(
                    TransactionIntakeController(
                        context.getBean(IntakeAggregate::class.java),
                        CadenceSource { _, _, _ -> cadence },
                        windows,
                        context.getBean(JooqTransactionStore::class.java),
                    ),
                ).setControllerAdvice(NotAggregatableAdvice())
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
                        StructureNode(it.second, null, false, it.first, code(it.first), emptyList(), 2),
                    ),
                )
            }
        }

        fun send(
            dates: List<LocalDate>,
            sourceKey: String = "K-1",
            revision: Int = 1,
        ): MockHttpServletResponse =
            scope.bind(TenantId(tenant), actor) {
                CorrelationContext.withCorrelation(CorrelationId("3fa85f64-5717-4562-b3fc-2c963f66afa6")) {
                    val items =
                        dates.joinToString(",") {
                            """{"transactionRef":"T-$it-$revision","transactionDate":"$it",""" +
                                """"internalTaxCode":"SR-STD","amount":"10.00"}"""
                        }
                    mvc
                        .perform(
                            post("/internal/v1/aggregation/transactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .content(
                                    """{"entityRef":"E-001","businessUnitRef":"BU-01","aggregateType":"PCP",
                                    |"underlyingRef":"BATCH-1","sourceKey":"$sourceKey","sourceRevision":$revision,
                                    |"transactions":[$items]}
                                    """.trimMargin(),
                                ),
                        ).andReturn()
                        .response
                }
            }

        fun close(
            periods: PeriodBoundarySource,
            tick: LocalDate,
        ): CloseResult = scope.bind(TenantId(tenant), actor) { CloseWindows(windows, periods, intake) {}.execute(tick) }
    }

    private fun run(
        dir: Path,
        cadence: Cadence,
        block: (Boundary) -> Unit,
    ) {
        AggregationContext.online(dir).run { context ->
            context.startupFailure?.let { throw it }
            block(Boundary(context, cadence))
        }
    }

    private fun days(
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()

    private fun rows(
        sql: String,
        tenant: UUID,
    ): List<String> =
        AggregationDb.superuser().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, tenant)
                statement.executeQuery().use { result ->
                    generateSequence { if (result.next()) result.getString(1) else null }.toList()
                }
            }
        }

    /** One line per aggregate: its period and the dates of the transactions it holds. */
    private fun aggregates(tenant: UUID): List<String> =
        rows(
            "select a.period_start || '..' || a.period_end || ' ' || coalesce(string_agg(t.transaction_date::text, " +
                "',' order by t.transaction_date), '') from aggregation.aggregate a left join " +
                "aggregation.aggregate_transaction t on t.aggregate_id = a.aggregate_id where a.tenant_id = ? " +
                "group by a.aggregate_id, a.period_start, a.period_end order by a.period_start",
            tenant,
        )

    private fun code(ref: String) = if (ref == "E-001") "E" else "B"

    private val week = days(LocalDate.of(2022, 3, 28), LocalDate.of(2022, 4, 3))
    private val twoQuarters = PeriodBoundarySource { _, _, _ -> PeriodSpans.Known(listOf(q1, q2)) }

    @Test
    fun `INV-AC-457 a weekly window from 28 Mar to 3 Apr is split into two aggregates at 31 Mar`(
        @TempDir dir: Path,
    ): Unit =
        run(dir, Cadence.WEEKLY) { boundary ->
            boundary.send(week).status shouldBe 202
            val result = boundary.close(twoQuarters, LocalDate.of(2022, 4, 4))
            result.held shouldBe emptyList()
            result.closed.size shouldBe 2
            aggregates(boundary.tenant) shouldBe
                listOf(
                    "2022-03-28..2022-03-31 2022-03-28,2022-03-29,2022-03-30,2022-03-31",
                    "2022-04-01..2022-04-03 2022-04-01,2022-04-02,2022-04-03",
                )
            rows(
                "select state from aggregation.aggregate_window where tenant_id = ?",
                boundary.tenant,
            ) shouldBe listOf("CLOSED")
        }

    @Test
    fun `INV-AC-457 closing the same tick again creates no third aggregate and moves no transaction`(
        @TempDir dir: Path,
    ): Unit =
        run(dir, Cadence.WEEKLY) { boundary ->
            boundary.send(week)
            boundary.close(twoQuarters, LocalDate.of(2022, 4, 4))
            val before = aggregates(boundary.tenant)
            boundary.close(twoQuarters, LocalDate.of(2022, 4, 4)).closed shouldBe emptyList()
            aggregates(boundary.tenant) shouldBe before
        }

    @Test
    fun `INV-AC-457 with the production NoPeriodBoundaries the window is held and no aggregate is made`(
        @TempDir dir: Path,
    ): Unit =
        run(dir, Cadence.WEEKLY) { boundary ->
            boundary.context.getBean(PeriodBoundarySource::class.java).javaClass shouldBe NoPeriodBoundaries::class.java
            boundary.send(week).status shouldBe 202
            val production = boundary.context.getBean(PeriodBoundarySource::class.java)
            val result = boundary.close(production, LocalDate.of(2022, 4, 4))
            result.closed shouldBe emptyList()
            result.held.size shouldBe 1
            aggregates(boundary.tenant) shouldBe emptyList()
            rows("select state from aggregation.aggregate_window where tenant_id = ?", boundary.tenant) shouldBe
                listOf("OPEN")
        }

    @Test
    fun `INV-AC-457 a window inside one period is closed as one aggregate holding all its transactions`(
        @TempDir dir: Path,
    ): Unit =
        run(dir, Cadence.WEEKLY) { boundary ->
            boundary.send(days(LocalDate.of(2022, 4, 4), LocalDate.of(2022, 4, 10)))
            boundary.close(twoQuarters, LocalDate.of(2022, 4, 11)).closed.size shouldBe 1
            aggregates(boundary.tenant).single() shouldBe
                "2022-04-04..2022-04-10 " + days(LocalDate.of(2022, 4, 4), LocalDate.of(2022, 4, 10)).joinToString(",")
        }

    @Test
    fun `a transaction dated 1 Apr is stored in the window of 1 Apr and one dated 31 Mar in the window of 31 Mar`(
        @TempDir dir: Path,
    ): Unit =
        run(dir, Cadence.DAILY) { boundary ->
            boundary.send(listOf(LocalDate.of(2022, 3, 31), LocalDate.of(2022, 4, 1))).status shouldBe 202
            rows(
                "select transaction_date || ' in ' || window_start || '..' || window_end from " +
                    "aggregation.aggregate_transaction where tenant_id = ? order by transaction_date",
                boundary.tenant,
            ) shouldBe listOf("2022-03-31 in 2022-03-31..2022-03-31", "2022-04-01 in 2022-04-01..2022-04-01")
        }

    @Test
    fun `a repeat of the same entity, source key and revision answers the original receipt and stores nothing`(
        @TempDir dir: Path,
    ): Unit =
        run(dir, Cadence.WEEKLY) { boundary ->
            val first = boundary.send(week.take(3))
            first.status shouldBe 202
            mapper.readTree(first.contentAsString).get("acceptedCount").asInt() shouldBe 3
            mapper.readTree(first.contentAsString).get("windowState").asString() shouldBe "OPEN"
            AggregationDb.superuser().use { connection ->
                connection
                    .prepareStatement("update aggregation.aggregate_window set state = 'LATE' where tenant_id = ?")
                    .use {
                        it.setObject(1, boundary.tenant)
                        it.executeUpdate()
                    }
            }
            val replay = boundary.send(week, revision = 1)
            replay.status shouldBe 202
            replay.contentAsString shouldBe first.contentAsString
            rows(
                "select count(*)::text from aggregation.aggregate_transaction where tenant_id = ?",
                boundary.tenant,
            ) shouldBe listOf("3")
        }

    @Test
    fun `the production source of period spans is NoPeriodBoundaries`(
        @TempDir dir: Path,
    ): Unit =
        run(dir, Cadence.DAILY) { boundary ->
            boundary.context
                .getBeansOfType(PeriodBoundarySource::class.java)
                .values
                .map { it.javaClass } shouldBe
                listOf(NoPeriodBoundaries::class.java)
        }
}
