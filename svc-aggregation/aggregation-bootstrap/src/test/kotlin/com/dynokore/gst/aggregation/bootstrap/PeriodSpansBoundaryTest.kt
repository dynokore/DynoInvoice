package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.AggregationDb
import com.dynokore.gst.aggregation.adapters.http.IntakeBodyCachingFilter
import com.dynokore.gst.aggregation.adapters.http.NotAggregatableAdvice
import com.dynokore.gst.aggregation.adapters.http.TransactionIntakeController
import com.dynokore.gst.aggregation.adapters.periods.ConsumedPeriodEvents
import com.dynokore.gst.aggregation.adapters.periods.NoPeriodBoundaries
import com.dynokore.gst.aggregation.adapters.periods.PeriodRecordsChangedHandler
import com.dynokore.gst.aggregation.adapters.periods.StoredPeriodBoundaries
import com.dynokore.gst.aggregation.adapters.persistence.JooqTransactionStore
import com.dynokore.gst.aggregation.application.AggregateRepository
import com.dynokore.gst.aggregation.application.CadenceSource
import com.dynokore.gst.aggregation.application.CloseWindows
import com.dynokore.gst.aggregation.application.IntakeAggregate
import com.dynokore.gst.aggregation.application.PeriodBoundarySource
import com.dynokore.gst.aggregation.application.ShortCodes
import com.dynokore.gst.aggregation.application.WindowRepository
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.JdbcInboxConsumer
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import com.dynokore.platform.outboxandqueue.CorrelationId as EventCorrelation

/**
 * The period spans read model wired into the composed service (INV-REQ-435, INV-Q-14444 A, INV-Q-16535 A): the
 * production source, the one consumer, and the split of a closed window over the stored spans, end to end.
 */
class PeriodSpansBoundaryTest {
    private val actor = "user:period-spans-test"

    private inner class Run(
        val context: ApplicationContext,
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        val tenant: UUID = UUID.randomUUID()
        val windows: WindowRepository = context.getBean(WindowRepository::class.java)
        private val intake =
            IntakeAggregate(context.getBean(ShortCodes::class.java), context.getBean(AggregateRepository::class.java))
        private val mvc: MockMvc =
            MockMvcBuilders
                .standaloneSetup(
                    TransactionIntakeController(
                        context.getBean(IntakeAggregate::class.java),
                        CadenceSource { _, _, _ -> Cadence.WEEKLY },
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

        fun sendWeek() {
            val items =
                days(LocalDate.of(2022, 3, 28), LocalDate.of(2022, 4, 3)).joinToString(",") {
                    """{"transactionRef":"T-$it","transactionDate":"$it","internalTaxCode":"SR-STD","amount":"10.00"}"""
                }
            scope.bind(TenantId(tenant), actor) {
                CorrelationContext.withCorrelation(CorrelationId("3fa85f64-5717-4562-b3fc-2c963f66afa6")) {
                    mvc
                        .perform(
                            post("/internal/v1/aggregation/transactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .content(
                                    """{"entityRef":"E-001","businessUnitRef":"BU-01","aggregateType":"PCP",
                                    |"underlyingRef":"BATCH-1","sourceKey":"K-1","sourceRevision":1,
                                    |"transactions":[$items]}
                                    """.trimMargin(),
                                ),
                        ).andReturn()
                        .response.status shouldBe 202
                }
            }
        }

        /** Delivers the event the way the inbox consumer does: the record's tenant bound, the handler called. */
        fun deliver(data: String): HandleOutcome =
            scope.bind(TenantId(tenant), actor) {
                context.getBean(PeriodRecordsChangedHandler::class.java).handle(
                    Envelope(
                        id = EventId(UUID.randomUUID().toString()),
                        source = "/gst/tenancy-svc",
                        type = ConsumedPeriodEvents.PERIOD_RECORDS_CHANGED,
                        subject = "E-001",
                        time = Instant.parse("2022-01-01T00:00:00Z"),
                        tenant = TenantId(tenant),
                        entity = EntityRef("E-001"),
                        docUuid = null,
                        transactionDate = null,
                        docSequence = null,
                        instanceId = "tenancy-svc-0",
                        correlation = EventCorrelation("c"),
                        causation = CausationId("k"),
                        dataJson = data,
                    ),
                )
            }

        fun close() =
            scope.bind(TenantId(tenant), actor) {
                CloseWindows(windows, context.getBean(PeriodBoundarySource::class.java), intake) {}
                    .execute(LocalDate.of(2022, 4, 4))
            }

        fun aggregates(): List<String> =
            AggregationDb.superuser().use { connection ->
                connection
                    .prepareStatement(
                        "select a.period_start || '..' || a.period_end || ' ' || coalesce(string_agg(" +
                            "t.transaction_date::text, ',' order by t.transaction_date), '') from " +
                            "aggregation.aggregate a left join aggregation.aggregate_transaction t on " +
                            "t.aggregate_id = a.aggregate_id where a.tenant_id = ? group by a.aggregate_id, " +
                            "a.period_start, a.period_end order by a.period_start",
                    ).use { statement ->
                        statement.setObject(1, tenant)
                        statement.executeQuery().use { rows ->
                            generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                        }
                    }
            }
    }

    private fun run(
        dir: Path,
        block: (Run) -> Unit,
    ) {
        AggregationContext.online(dir).run { context ->
            context.startupFailure?.let { throw it }
            block(Run(context))
        }
    }

    private fun days(
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> = generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()

    private val twoPeriods =
        """{"entityRef":"E-001","version":1,"periods":[
        |{"periodRef":"2022-Q1","periodStart":"2022-01-01","periodEnd":"2022-03-31"},
        |{"periodRef":"2022-Q2","periodStart":"2022-04-01","periodEnd":"2022-06-30"}]}
        """.trimMargin()

    @Test
    fun `the only bean of type PeriodBoundarySource is StoredPeriodBoundaries and NoPeriodBoundaries is not a bean`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            boundary.context
                .getBeansOfType(PeriodBoundarySource::class.java)
                .values
                .map { it.javaClass } shouldBe listOf(StoredPeriodBoundaries::class.java)
            boundary.context.getBeansOfType(NoPeriodBoundaries::class.java).size shouldBe 0
        }

    @Test
    fun `the period records consumer is on the topic gst tenancy period-records-changed v1 only`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            boundary.context.getBeansOfType(JdbcInboxConsumer::class.java).size shouldBe 1
            val subscription =
                boundary.context.getBean(
                    "periodRecordsChangedSubscription",
                    com.dynokore.platform.outboxandqueue.ConsumerSubscription::class.java,
                )
            subscription.topic shouldBe "gst.tenancy.period-records-changed.v1"
            ConsumedPeriodEvents.PERIOD_RECORDS_CHANGED shouldBe "com.dynokore.gst.tenancy.PeriodRecordsChanged.v1"
            boundary.context.getBeansOfType(PeriodRecordsChangedHandler::class.java).size shouldBe 1
        }

    @Test
    fun `INV-AC-14492 with an event of 1 Jan to 31 Mar and 1 Apr to 30 Jun the weekly window is split at 31 Mar`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            boundary.sendWeek()
            boundary.deliver(twoPeriods) shouldBe HandleOutcome.APPLIED
            val result = boundary.close()
            result.held shouldBe emptyList()
            result.closed.size shouldBe 2
            boundary.aggregates() shouldBe
                listOf(
                    "2022-03-28..2022-03-31 2022-03-28,2022-03-29,2022-03-30,2022-03-31",
                    "2022-04-01..2022-04-03 2022-04-01,2022-04-02,2022-04-03",
                )
        }

    @Test
    fun `INV-AC-14492 before any event the window stays open and nothing is created`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            boundary.sendWeek()
            val result = boundary.close()
            result.closed shouldBe emptyList()
            result.held.size shouldBe 1
            boundary.aggregates() shouldBe emptyList()
        }
}
