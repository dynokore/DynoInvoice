package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.AggregationDb
import com.dynokore.gst.aggregation.adapters.events.ConsumedSubmissionEvents
import com.dynokore.gst.aggregation.adapters.http.IntakeBodyCachingFilter
import com.dynokore.gst.aggregation.adapters.http.NotAggregatableAdvice
import com.dynokore.gst.aggregation.adapters.http.TransactionIntakeController
import com.dynokore.gst.aggregation.adapters.persistence.JooqTransactionStore
import com.dynokore.gst.aggregation.application.AggregateRepository
import com.dynokore.gst.aggregation.application.CadenceSource
import com.dynokore.gst.aggregation.application.CloseWindows
import com.dynokore.gst.aggregation.application.ExcludeReplacedReceipt
import com.dynokore.gst.aggregation.application.HandOnAggregates
import com.dynokore.gst.aggregation.application.IntakeAggregate
import com.dynokore.gst.aggregation.application.PeriodBoundarySource
import com.dynokore.gst.aggregation.application.PeriodSpans
import com.dynokore.gst.aggregation.application.ShortCodes
import com.dynokore.gst.aggregation.application.WindowChangePublisher
import com.dynokore.gst.aggregation.application.WindowRepository
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.gst.aggregation.domain.PeriodSpan
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.Envelope
import com.dynokore.platform.outboxandqueue.EventHandler
import com.dynokore.platform.outboxandqueue.EventId
import com.dynokore.platform.outboxandqueue.HandleOutcome
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.serviceruntime.logging.CorrelationId
import com.dynokore.platform.serviceruntime.replica.OrganisationStructureChanged
import com.dynokore.platform.serviceruntime.replica.StructureChange
import com.dynokore.platform.serviceruntime.replica.StructureNode
import com.dynokore.platform.serviceruntime.replica.StructureNodeKind
import com.dynokore.platform.serviceruntime.replica.StructureReplica
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
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
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import com.dynokore.platform.outboxandqueue.CorrelationId as EventCorrelation

/**
 * A late transaction after SubmissionDespatched ends in a supplementary aggregate and never in the transmitted one
 * (INV-REQ-439, INV-AC-24211), over the composed service and the database: the real SubmissionDespatched handler, the
 * real intakeAggregationTransactions controller and the closing run. The cadence and the period span are test doubles
 * of the two ports, as in [TransactionIntakeBoundaryTest].
 */
class LateTransactionBoundaryTest {
    private val actor = "user:late-transaction-test"
    private val day = LocalDate.of(2027, 3, 10)
    private val tick = LocalDate.of(2027, 3, 15)
    private val march = PeriodSpan(LocalDate.of(2027, 3, 1), LocalDate.of(2027, 3, 31), "2027-03")
    private val periods = PeriodBoundarySource { _, _, _ -> PeriodSpans.Known(listOf(march)) }

    private inner class Boundary(
        val context: ApplicationContext,
        val tenant: UUID = UUID.randomUUID(),
    ) {
        val scope: TenantScope = context.getBean(TenantScope::class.java)
        private val windows: WindowRepository = context.getBean(WindowRepository::class.java)
        private val handOn = context.getBean(HandOnAggregates::class.java)
        private val intake =
            IntakeAggregate(context.getBean(ShortCodes::class.java), context.getBean(AggregateRepository::class.java))

        private fun mvcWith(publisher: WindowChangePublisher): MockMvc =
            MockMvcBuilders
                .standaloneSetup(
                    TransactionIntakeController(
                        context.getBean(IntakeAggregate::class.java),
                        CadenceSource { _, _, _ -> Cadence.DAILY },
                        windows,
                        context.getBean(JooqTransactionStore::class.java),
                        context.getBean(ExcludeReplacedReceipt::class.java),
                        periods,
                        publisher,
                        context.getBean(TransactionRunner::class.java),
                    ),
                ).setControllerAdvice(NotAggregatableAdvice())
                .addFilters<StandaloneMockMvcBuilder>(IntakeBodyCachingFilter())
                .build()

        private val mvc: MockMvc = mvcWith(context.getBean(WindowChangePublisher::class.java))

        init {
            val structure = context.getBean(StructureReplica::class.java)
            listOf(
                Triple("E-001", StructureNodeKind.ENTITY, "E"),
                Triple("E-002", StructureNodeKind.ENTITY, "F"),
                Triple("BU-01", StructureNodeKind.BUSINESS_UNIT, "B"),
            ).forEach {
                structure.accept(
                    OrganisationStructureChanged(
                        TenantId(tenant),
                        it.first,
                        StructureChange.UPDATED,
                        StructureNode(it.second, null, false, it.first, it.third, emptyList(), 2),
                    ),
                )
            }
        }

        fun send(
            ref: String,
            key: String,
            entity: String = "E-001",
            date: LocalDate = day,
            via: MockMvc = mvc,
        ): MockHttpServletResponse =
            scope.bind(TenantId(tenant), actor) {
                CorrelationContext.withCorrelation(CorrelationId("3fa85f64-5717-4562-b3fc-2c963f66afa6")) {
                    via
                        .perform(
                            post("/internal/v1/aggregation/transactions")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .content(
                                    """{"entityRef":"$entity","businessUnitRef":"BU-01","aggregateType":"PCP",
                                    |"underlyingRef":"BATCH-1","sourceKey":"$key","sourceRevision":1,
                                    |"transactions":[{"transactionRef":"$ref","transactionDate":"$date",
                                    |"internalTaxCode":"SR-STD","amount":"10.00","claimableInputTax":"10.00"}]}
                                    """.trimMargin(),
                                ),
                        ).andReturn()
                        .response
                }
            }

        /** The controller of the real beans whose publisher fails after it appended the event (a rollback probe). */
        fun failingAfterPublish(): MockMvc {
            val real = context.getBean(WindowChangePublisher::class.java)
            return mvcWith(
                WindowChangePublisher {
                    real.changed(it)
                    error("the intake fails after the event was appended")
                },
            )
        }

        fun close(
            on: LocalDate,
            publisher: WindowChangePublisher = WindowChangePublisher {},
        ) {
            scope.bind(TenantId(tenant), actor) {
                // the real publisher keeps its counter in the transaction of the caller, as the closing job does
                scope.transactions.inTransaction { CloseWindows(windows, periods, intake, publisher).execute(on) }
            }
        }

        fun handOn(): Int =
            scope.bind(TenantId(tenant), actor) { scope.transactions.inTransaction { handOn.execute("E-001") } }.size

        /** SubmissionDespatched delivered to the real handler of [as]'s tenant, naming [aggregateId]. */
        fun despatched(
            aggregateId: String,
            `as`: UUID = tenant,
        ): HandleOutcome =
            scope.bind(TenantId(`as`), actor) {
                context.getBean("submissionDespatchedHandler", EventHandler::class.java).handle(
                    Envelope(
                        id = EventId(UUID.randomUUID().toString()),
                        source = "/gst/submission-svc",
                        type = ConsumedSubmissionEvents.SUBMISSION_DESPATCHED,
                        subject = "E-001",
                        time = Instant.parse("2027-03-12T00:00:00Z"),
                        tenant = TenantId(`as`),
                        entity = EntityRef("E-001"),
                        docUuid = null,
                        transactionDate = null,
                        docSequence = null,
                        instanceId = "submission-svc-0",
                        correlation = EventCorrelation("c"),
                        causation = CausationId("k"),
                        dataJson =
                            """{"docUuid":"${UUID.randomUUID()}","version":1,"attemptNumber":1,""" +
                                """"payloadSha256":"${"a".repeat(64)}","aggregateId":"$aggregateId"}""",
                    ),
                )
            }
    }

    private fun rows(sql: String): List<String> =
        AggregationDb.superuser().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { result ->
                    generateSequence { if (result.next()) result.getString(1) else null }.toList()
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

    /** The first transaction of [day] closed into aggregate A and handed on; A's id. */
    private fun handedOnAggregate(boundary: Boundary): String {
        boundary.send("T-FIRST", "K-1").status shouldBe 202
        boundary.close(tick)
        boundary.handOn() shouldBe 1
        return aggregates(boundary.tenant).single()
    }

    private fun transmitted(id: String) =
        rows("select (transmitted_at is not null)::text from aggregation.aggregate where aggregate_id = '$id'").single()

    private fun sequenceOf(id: String) =
        rows("select sequence::text from aggregation.aggregate where aggregate_id = '$id'").single().toLong()

    private fun rowOf(id: String) =
        rows("select to_jsonb(a)::text from aggregation.aggregate a where aggregate_id = '$id'").single()

    private fun linesOf(id: String) =
        rows(
            "select to_jsonb(l)::text from aggregation.aggregate_line l where aggregate_id = '$id' order by position",
        )

    private fun aggregates(tenant: UUID) =
        rows("select aggregate_id::text from aggregation.aggregate where tenant_id = '$tenant' order by sequence")

    private fun assigned(
        tenant: UUID,
        ref: String,
    ) = rows(
        "select coalesce(aggregate_id::text, 'null') from aggregation.aggregate_transaction " +
            "where tenant_id = '$tenant' and transaction_ref = '$ref'",
    ).single()

    @Test
    fun `INV-AC-24211 a late transaction after SubmissionDespatched is in a supplementary aggregate and A is unchanged`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val a = handedOnAggregate(boundary)
            boundary.despatched(a) shouldBe HandleOutcome.APPLIED
            transmitted(a) shouldBe "true"
            val rowA = rowOf(a)
            val linesA = linesOf(a)
            val sequenceA = sequenceOf(a)

            boundary.send("T-LATE", "K-2").status shouldBe 202
            assigned(boundary.tenant, "T-LATE") shouldBe "null"
            boundary.close(tick.plusDays(1))

            val all = aggregates(boundary.tenant)
            all.size shouldBe 2
            val supplementary = assigned(boundary.tenant, "T-LATE")
            (supplementary != a && supplementary != "null") shouldBe true
            all shouldBe listOf(a, supplementary)
            rows("select supplementary::text from aggregation.aggregate where aggregate_id = '$supplementary'") shouldBe
                listOf("true")
            sequenceOf(supplementary) shouldBe sequenceA + 1
            (rowOf(supplementary) != rowA) shouldBe true
            rows(
                "select count(distinct document_number)::text from aggregation.aggregate " +
                    "where aggregate_id in ('$a', '$supplementary')",
            ) shouldBe listOf("2")
            rows(
                "select transaction_ref from aggregation.aggregate_transaction " +
                    "where aggregate_id = '$supplementary' order by 1",
            ) shouldBe listOf("T-LATE")
            rowOf(a) shouldBe rowA
            linesOf(a) shouldBe linesA

            val afterFirst = all.map { rowOf(it) to linesOf(it) }
            boundary.close(tick.plusDays(1))
            boundary.close(tick.plusDays(2))
            aggregates(boundary.tenant) shouldBe all
            all.map { rowOf(it) to linesOf(it) } shouldBe afterFirst
            assigned(boundary.tenant, "T-LATE") shouldBe supplementary
        }

    @Test
    fun `INV-AC-24211 an event naming another tenant's aggregate leaves A unmarked and the late transaction unassigned`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val a = handedOnAggregate(boundary)
            val other = Boundary(boundary.context)
            val foreign = handedOnAggregate(other)
            boundary.despatched(foreign) shouldBe HandleOutcome.IGNORED
            transmitted(a) shouldBe "false"
            transmitted(foreign) shouldBe "false"
            val rowA = rowOf(a)

            boundary.send("T-LATE", "K-2").status shouldBe 202
            boundary.close(tick.plusDays(1))

            aggregates(boundary.tenant) shouldBe listOf(a)
            assigned(boundary.tenant, "T-LATE") shouldBe "null"
            rowOf(a) shouldBe rowA
        }

    private fun lateRows(tenant: UUID) =
        rows(
            "select w.entity_ref || ':' || l.part_start || '..' || l.part_end from " +
                "aggregation.aggregate_window_part_late l join aggregation.aggregate_window w " +
                "on w.window_id = l.window_id where l.tenant_id = '$tenant' order by 1",
        )

    private fun events(tenant: UUID) =
        rows(
            "select (data->>'entityRef') || ':' || (data->>'state') || ':' || (data->>'stateVersion') || ':' || " +
                "coalesce(data->>'aggregateId', 'none') from aggregation.outbox " +
                "where tenant_id = '$tenant' and type like '%AggregateWindowChanged%' order by seq",
        )

    private fun transactionRows(tenant: UUID) =
        rows(
            "select to_jsonb(t)::text from aggregation.aggregate_transaction t where tenant_id = '$tenant' " +
                "and aggregate_id is not null order by transaction_ref",
        )

    @Test
    fun `INV-AC-24763 a transaction dated inside a closed part records it LATE once and publishes LATE once`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant
            boundary.send("T-FIRST", "K-1").status shouldBe 202
            boundary.send("T-E2", "K-E2", entity = "E-002").status shouldBe 202
            boundary.close(tick, boundary.context.getBean(WindowChangePublisher::class.java))
            val agg1 = aggregates(tenant).first()
            val rowsBefore = aggregates(tenant).map { rowOf(it) to linesOf(it) }
            val transactionsBefore = transactionRows(tenant)
            events(tenant).size shouldBe 2

            boundary.send("T-LATE", "K-2").status shouldBe 202
            assigned(tenant, "T-LATE") shouldBe "null"
            lateRows(tenant) shouldBe listOf("E-001:2027-03-10..2027-03-10")
            events(tenant).filter { it.startsWith("E-001:") } shouldBe
                listOf("E-001:CLOSED:1:$agg1", "E-001:LATE:2:none")

            boundary.send("T-LATE2", "K-3").status shouldBe 202
            assigned(tenant, "T-LATE2") shouldBe "null"
            lateRows(tenant) shouldBe listOf("E-001:2027-03-10..2027-03-10")
            events(tenant).count { it.startsWith("E-001:LATE") } shouldBe 1

            boundary.send("T-OPEN", "K-4", date = LocalDate.of(2027, 3, 20)).status shouldBe 202
            assigned(tenant, "T-OPEN") shouldBe "null"
            lateRows(tenant) shouldBe listOf("E-001:2027-03-10..2027-03-10")
            events(tenant).size shouldBe 3

            boundary.send("T-E2-LATE", "K-E2L", entity = "E-002").status shouldBe 202
            lateRows(tenant) shouldBe
                listOf("E-001:2027-03-10..2027-03-10", "E-002:2027-03-10..2027-03-10")
            events(tenant).filter { it.startsWith("E-002:") }.map { it.substringBeforeLast(":") } shouldBe
                listOf("E-002:CLOSED:1", "E-002:LATE:2")

            aggregates(tenant).map { rowOf(it) to linesOf(it) } shouldBe rowsBefore
            transactionRows(tenant) shouldBe transactionsBefore
        }

    @Test
    fun `INV-AC-24763 the LATE row and its event roll back together when the intake fails after the event`(
        @TempDir dir: Path,
    ): Unit =
        run(dir) { boundary ->
            val tenant = boundary.tenant
            boundary.send("T-FIRST", "K-1").status shouldBe 202
            boundary.close(tick, boundary.context.getBean(WindowChangePublisher::class.java))
            val eventsBefore = events(tenant)
            val versionsBefore =
                rows("select state_version::text from aggregation.window_state_version where tenant_id = '$tenant'")

            runCatching { boundary.send("T-LATE", "K-2", via = boundary.failingAfterPublish()) }.isFailure shouldBe true

            lateRows(tenant) shouldBe emptyList()
            events(tenant) shouldBe eventsBefore
            rows(
                "select state_version::text from aggregation.window_state_version where tenant_id = '$tenant'",
            ) shouldBe
                versionsBefore
            rows(
                "select count(*)::text from aggregation.aggregate_transaction where tenant_id = '$tenant' " +
                    "and transaction_ref = 'T-LATE'",
            ) shouldBe listOf("0")
        }
}
