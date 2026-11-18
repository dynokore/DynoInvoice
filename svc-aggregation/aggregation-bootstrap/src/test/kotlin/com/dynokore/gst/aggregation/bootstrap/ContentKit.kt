package com.dynokore.gst.aggregation.bootstrap

import com.dynokore.gst.aggregation.adapters.AggregationDb
import com.dynokore.gst.aggregation.adapters.events.AggregateReadyOutbox
import com.dynokore.gst.aggregation.adapters.http.DrillDownController
import com.dynokore.gst.aggregation.adapters.http.DrillDownCursors
import com.dynokore.gst.aggregation.adapters.http.IntakeBodyCachingFilter
import com.dynokore.gst.aggregation.adapters.http.NotAggregatableAdvice
import com.dynokore.gst.aggregation.adapters.http.TransactionIntakeController
import com.dynokore.gst.aggregation.adapters.persistence.JooqTransactionStore
import com.dynokore.gst.aggregation.application.AggregateRepository
import com.dynokore.gst.aggregation.application.CadenceSource
import com.dynokore.gst.aggregation.application.CloseWindows
import com.dynokore.gst.aggregation.application.ExcludeReplacedReceipt
import com.dynokore.gst.aggregation.application.GetAggregateDrillDown
import com.dynokore.gst.aggregation.application.HandOnAggregates
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
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.util.UUID

/** One transaction of a posted request. */
data class Txn(
    val ref: String,
    val code: String,
    val amount: String,
    val date: LocalDate = LocalDate.of(2022, 3, 10),
    val claimant: String? = null,
    val claimable: String? = null,
)

/**
 * The aggregate-content tests' fixture (INV-T-4980): the real transaction-intake and drill-down controllers over the
 * composed service and the database, the closing run with a daily cadence and the Q1 2022 span, and the hand-off.
 */
class ContentKit(
    val context: ApplicationContext,
    var actor: String = "user:content-test",
) {
    val mapper = ObjectMapper()
    val scope: TenantScope = context.getBean(TenantScope::class.java)
    val tenant: UUID = UUID.randomUUID()
    private val windows: WindowRepository = context.getBean(WindowRepository::class.java)
    private val intake =
        IntakeAggregate(context.getBean(ShortCodes::class.java), context.getBean(AggregateRepository::class.java))
    private val spans =
        PeriodBoundarySource {
            _,
            _,
            _,
            ->
            PeriodSpans.Known(listOf(PeriodSpan(LocalDate.of(2022, 1, 1), LocalDate.of(2022, 3, 31), "2022-Q1")))
        }
    private val mvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                TransactionIntakeController(
                    context.getBean(IntakeAggregate::class.java),
                    CadenceSource { _, _, _ -> Cadence.DAILY },
                    windows,
                    context.getBean(JooqTransactionStore::class.java),
                    context.getBean(ExcludeReplacedReceipt::class.java),
                    context.getBean(com.dynokore.gst.aggregation.application.PeriodBoundarySource::class.java),
                    context.getBean(com.dynokore.gst.aggregation.application.WindowChangePublisher::class.java),
                    context.getBean(com.dynokore.platform.tenancykernel.TransactionRunner::class.java),
                ),
                DrillDownController(
                    context.getBean(GetAggregateDrillDown::class.java),
                    context.getBean(DrillDownCursors::class.java),
                    context.getBean(TenantContext::class.java),
                ),
            ).setControllerAdvice(NotAggregatableAdvice())
            .addFilters<StandaloneMockMvcBuilder>(IntakeBodyCachingFilter())
            .build()

    init {
        val structure = context.getBean(StructureReplica::class.java)
        listOf(
            Triple("E-001", StructureNodeKind.ENTITY, "E"),
            Triple("E-002", StructureNodeKind.ENTITY, "F"),
            Triple("BU-01", StructureNodeKind.BUSINESS_UNIT, "B"),
        ).forEach { (ref, kind, code) ->
            structure.accept(
                OrganisationStructureChanged(
                    TenantId(tenant),
                    ref,
                    StructureChange.UPDATED,
                    StructureNode(kind, null, false, ref, code, emptyList(), 2),
                ),
            )
        }
    }

    fun <T> bound(block: () -> T): T =
        scope.bind(TenantId(tenant), actor) {
            CorrelationContext.withCorrelation(CorrelationId("3fa85f64-5717-4562-b3fc-2c963f66afa6")) { block() }
        }

    @Suppress("LongParameterList")
    fun post(
        txns: List<Txn>,
        type: String = "POS",
        creditNote: Boolean? = null,
        sourceKey: String = "K-${UUID.randomUUID()}",
        entity: String = "E-001",
        underlyingRef: String = "BATCH-1",
    ): MockHttpServletResponse =
        bound {
            val items =
                txns.joinToString(",") {
                    """{"transactionRef":"${it.ref}","transactionDate":"${it.date}",""" +
                        """"internalTaxCode":"${it.code}","amount":"${it.amount}"""" +
                        it.claimant?.let { c -> ""","claimantReference":"$c"""" }.orEmpty() +
                        it.claimable?.let { c -> ""","claimableInputTax":"$c"""" }.orEmpty() + "}"
                }
            val flag = creditNote?.let { ""","isCreditNote":$it""" }.orEmpty()
            mvc
                .perform(
                    post("/internal/v1/aggregation/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content(
                            """{"entityRef":"$entity","businessUnitRef":"BU-01","aggregateType":"$type",
                            |"underlyingRef":"$underlyingRef","sourceKey":"$sourceKey","sourceRevision":1,
                            |"transactions":[$items]$flag}
                            """.trimMargin(),
                        ),
                ).andReturn()
                .response
        }

    fun exclude(
        entity: String,
        receipt: String,
    ): MockHttpServletResponse =
        bound {
            mvc
                .perform(
                    post("/internal/v1/aggregation/replaced-receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content("""{"entityRef":"$entity","receiptNumber":"$receipt"}"""),
                ).andReturn()
                .response
        }

    fun drill(
        aggregateId: String,
        limit: Int? = null,
        cursor: String? = null,
    ): MockHttpServletResponse =
        bound {
            val request = get("/internal/v1/aggregation/underlying-transactions").param("aggregateId", aggregateId)
            limit?.let { request.param("limit", it.toString()) } ?: request.param("limit", "100")
            cursor?.let { request.param("cursor", it) }
            mvc.perform(request).andReturn().response
        }

    /** The closing run for the business day after the transactions' day. */
    fun close(tick: LocalDate = LocalDate.of(2022, 3, 11)) =
        bound { CloseWindows(windows, spans, intake) {}.execute(tick) }

    fun handOn(entity: String = "E-001") =
        bound { scope.transactions.inTransaction { context.getBean(HandOnAggregates::class.java).execute(entity) } }

    fun json(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.contentAsString)

    fun rows(
        sql: String,
        vararg args: Any,
    ): List<String> =
        AggregationDb.superuser().use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setObject(1, tenant)
                args.forEachIndexed { index, value -> statement.setObject(index + 2, value) }
                statement.executeQuery().use { result ->
                    generateSequence { if (result.next()) result.getString(1) else null }.toList()
                }
            }
        }

    fun update(sql: String) =
        AggregationDb.superuser().use { connection -> connection.createStatement().use { it.executeUpdate(sql) } }

    /** `S=15.00`-style lines of the aggregate, in stored order. */
    fun lines(aggregateId: String): List<String> =
        rows(
            "select internal_tax_code || '=' || amount from aggregation.aggregate_line " +
                "where tenant_id = ? and aggregate_id = '$aggregateId' order by position",
        )

    /** The aggregates of the tenant for one entity, as `id` strings, ordered by sequence. */
    fun aggregateIds(
        creditNote: Boolean,
        entity: String = "E-001",
    ): List<String> =
        rows(
            "select aggregate_id::text from aggregation.aggregate where tenant_id = ? and credit_note = $creditNote " +
                "and entity_ref = '$entity' order by sequence",
        )

    fun ready(): List<JsonNode> =
        rows(
            "select data from aggregation.outbox where tenant_id = ? " +
                "and type = '${AggregateReadyOutbox.TYPE}' order by 1",
        ).map { mapper.readTree(it) }
}
