package com.dynokore.gst.aggregation.adapters

import com.dynokore.gst.aggregation.adapters.http.TransactionIntakeController
import com.dynokore.gst.aggregation.application.AggregateRepository
import com.dynokore.gst.aggregation.application.IntakeAggregate
import com.dynokore.gst.aggregation.application.NewAggregate
import com.dynokore.gst.aggregation.application.PeriodSpans
import com.dynokore.gst.aggregation.application.ShortCodes
import com.dynokore.gst.aggregation.application.StoredAggregate
import com.dynokore.gst.aggregation.application.WindowRepository
import com.dynokore.gst.aggregation.contract.model.AggregationBatchRequest
import com.dynokore.gst.aggregation.contract.model.AggregationTransactionsRequest
import com.dynokore.gst.aggregation.contract.model.AggregationTransactionsRequestTransactionsInner
import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.AggregationWindow
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.gst.aggregation.domain.PeriodSpan
import com.dynokore.gst.aggregation.domain.WindowState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The two refusals the aggregation contract promises, answered by the controllers over their use cases:
 * ING-002 on the transactions route (409, over the real store) and ING-018 on the batch route (422).
 */
class RefusalMappingTest {
    private val windows =
        object : WindowRepository {
            override fun place(
                entityRef: String,
                businessUnitRef: String,
                aggregateType: com.dynokore.gst.aggregation.domain.AggregateType,
                date: LocalDate,
                cadence: Cadence,
            ) = com.dynokore.gst.aggregation.application.Placement(
                AggregationWindow.containing(cadence, date),
                WindowState.OPEN,
            )

            override fun openWindowsEndingOnOrBefore(date: LocalDate) =
                emptyList<com.dynokore.gst.aggregation.application.OpenWindow>()

            override fun isPartClosed(
                window: com.dynokore.gst.aggregation.application.OpenWindow,
                part: com.dynokore.gst.aggregation.domain.WindowPart,
            ) = false

            override fun closePart(
                window: com.dynokore.gst.aggregation.application.OpenWindow,
                part: com.dynokore.gst.aggregation.domain.WindowPart,
                aggregateId: UUID,
            ) = Unit

            override fun recordLate(window: com.dynokore.gst.aggregation.application.OpenWindow) = Unit
        }

    private val noAggregates =
        object : AggregateRepository {
            override fun findByIntakeKey(
                entityRef: String,
                sourceKey: String,
                sourceRevision: Long,
            ): StoredAggregate? = null

            override fun highestRevision(
                entityRef: String,
                sourceKey: String,
            ): Long? = null

            override fun nextSequence(): Long = error("a refused intake takes no sequence")

            override fun store(aggregate: NewAggregate): StoredAggregate = error("a refused intake stores nothing")
        }

    private val codes =
        object : ShortCodes {
            override fun entityCode(entityRef: String): String = "E"

            override fun businessUnitCode(businessUnitRef: String): String = "B"
        }

    private fun transactionsRequest(revision: Int) =
        AggregationTransactionsRequest(
            entityRef = "E-RM",
            businessUnitRef = "BU-01",
            aggregateType = AggregationTransactionsRequest.AggregateType.PCP,
            underlyingRef = "BATCH-1",
            sourceKey = "K-RM",
            sourceRevision = revision,
            transactions =
                listOf(
                    AggregationTransactionsRequestTransactionsInner(
                        "T-$revision",
                        LocalDate.of(2022, 3, 31),
                        "SR-STD",
                        BigDecimal("10.00"),
                    ),
                ),
        )

    private fun send(
        controller: TransactionIntakeController,
        revision: Int,
    ) = controller.intakeAggregationTransactions(UUID.randomUUID(), transactionsRequest(revision)).statusCode.value()

    private fun code(body: Any?): String? = JsonMapper().readTree(body as String).path("code").asString()

    @Test
    fun `INV-AC-20110 a lower revision is answered 409 with the Problem code ING-002 and stored nothing`() {
        val store = transactionStoreOf(UUID.randomUUID())
        val controller =
            TransactionIntakeController(
                IntakeAggregate(codes, noAggregates),
                { _, _, _ -> Cadence.MONTHLY },
                windows,
                store,
            )
        send(controller, 5) shouldBe 202

        val refused = controller.intakeAggregationTransactions(UUID.randomUUID(), transactionsRequest(4))
        refused.statusCode.value() shouldBe 409
        code(refused.body) shouldBe "ING-002"
        store.scoped("BU-01", AggregateType.PCP).highestRevision("E-RM", "K-RM") shouldBe 5L

        send(controller, 5) shouldBe 202
        send(controller, 6) shouldBe 202
        store.scoped("BU-01", AggregateType.PCP).highestRevision("E-RM", "K-RM") shouldBe 6L
    }

    @Test
    fun `ING-018 a request crossing a period boundary is answered 422 with the Problem code ING-018`() {
        val spans =
            PeriodSpans.Known(
                listOf(
                    PeriodSpan(LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31), "2027-Q1"),
                    PeriodSpan(LocalDate.of(2027, 4, 1), LocalDate.of(2027, 6, 30), "2027-Q2"),
                ),
            )
        val controller =
            com.dynokore.gst.aggregation.adapters.http.IntakeController(
                IntakeAggregate(codes, noAggregates, periods = { _, _, _ -> spans }),
            )
        val request =
            AggregationBatchRequest(
                entityRef = "E-RM",
                businessUnitRef = "BU-01",
                aggregateType = AggregationBatchRequest.AggregateType.PCP,
                periodStart = LocalDate.of(2027, 3, 28),
                periodEnd = LocalDate.of(2027, 4, 3),
                underlyingRef = "BATCH-1",
                linesByInternalTaxCode = emptyList(),
                sourceKey = "K-RM",
                sourceRevision = 1,
            )
        val refused = controller.intakeAggregationBatch(UUID.randomUUID(), request)
        refused.statusCode.value() shouldBe 422
        code(refused.body) shouldBe "ING-018"
    }
}
