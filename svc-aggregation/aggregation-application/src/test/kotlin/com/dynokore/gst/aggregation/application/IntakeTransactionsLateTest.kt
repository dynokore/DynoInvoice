package com.dynokore.gst.aggregation.application

import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.AggregationWindow
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.gst.aggregation.domain.PeriodSpan
import com.dynokore.gst.aggregation.domain.WindowPart
import com.dynokore.gst.aggregation.domain.WindowState
import com.dynokore.platform.tenancykernel.TransactionRunner
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/** INV-AC-24763: a transaction dated inside a closed window part records it LATE once and publishes LATE once. */
class IntakeTransactionsLateTest {
    private var depth = 0

    private val runner =
        object : TransactionRunner {
            override fun <T> inTransaction(block: () -> T): T {
                depth++
                try {
                    return block()
                } finally {
                    depth--
                }
            }
        }

    private class Late(
        val window: OpenWindow,
        val part: WindowPart,
        val inTransaction: Boolean,
    )

    private val recorded = mutableListOf<Late>()
    private val published = mutableListOf<Pair<WindowChange, Boolean>>()
    private val closedWrites = mutableListOf<UUID>()
    private val order = mutableListOf<String>()
    private val lateKeys = mutableSetOf<Pair<String, LocalDate>>()

    /** The closed parts: (entity, part start). */
    private val closed = mutableSetOf<Pair<String, LocalDate>>()

    private val windows =
        object : WindowRepository {
            override fun place(
                entityRef: String,
                businessUnitRef: String,
                aggregateType: AggregateType,
                date: LocalDate,
                cadence: Cadence,
            ) = Placement(AggregationWindow.containing(cadence, date), WindowState.OPEN)

            override fun openWindowsStartingOnOrBefore(date: LocalDate) = emptyList<OpenWindow>()

            override fun isPartClosed(
                window: OpenWindow,
                part: WindowPart,
            ) = (window.entityRef to part.start) in closed

            override fun closePart(
                window: OpenWindow,
                part: WindowPart,
                aggregateId: UUID,
            ) {
                closedWrites += aggregateId
            }

            override fun closeCreditNotes(
                window: OpenWindow,
                part: WindowPart,
                aggregateId: UUID,
            ) {
                closedWrites += aggregateId
            }

            override fun assignLate(
                late: LatePart,
                aggregateId: UUID,
            ) {
                closedWrites += aggregateId
            }

            override fun recordLate(
                window: OpenWindow,
                part: WindowPart,
            ): Boolean {
                order += "recordLate"
                recorded += Late(window, part, depth > 0)
                return lateKeys.add(window.entityRef to part.start)
            }
        }

    private val store =
        object : TransactionRepository {
            val stored = mutableListOf<PlacedTransaction>()

            override fun find(
                entityRef: String,
                sourceKey: String,
                sourceRevision: Long,
            ): List<PlacedTransaction>? = null

            override fun highestRevision(
                entityRef: String,
                sourceKey: String,
            ): Long? = null

            override fun store(
                entityRef: String,
                sourceKey: String,
                sourceRevision: Long,
                placed: List<PlacedTransaction>,
            ) {
                order += "store"
                stored += placed
            }
        }

    private fun day(
        m: Int,
        d: Int,
    ) = LocalDate.of(2027, m, d)

    private val spans =
        PeriodSpans.Known(
            listOf(
                PeriodSpan(day(3, 1), day(3, 31), "2027-03"),
                PeriodSpan(day(4, 1), day(4, 30), "2027-04"),
            ),
        )

    private val publisher =
        WindowChangePublisher {
            order += "publish"
            published += it to (depth > 0)
        }

    private val intake =
        IntakeTransactions({ _, _, _ -> Cadence.WEEKLY }, windows, store, { _, _, _ -> spans }, publisher, runner)

    private fun run(
        entity: String,
        vararg dates: LocalDate,
    ) = intake.execute(
        TransactionsCommand(
            entity,
            "BU-1",
            AggregateType.POS,
            "K-${UUID.randomUUID()}",
            1,
            dates.mapIndexed { i, date -> DatedTransaction("T$i-$date", date, "SR-STD", "10.00") },
        ),
    )

    init {
        closed += "E1" to day(3, 29)
        closed += "E2" to day(3, 29)
    }

    @Test
    fun `INV-AC-24763 a transaction dated inside a closed part records it once and publishes LATE once`() {
        val outcome = run("E1", day(3, 30))
        recorded.map { it.part } shouldBe listOf(WindowPart(day(3, 29), day(3, 31), "2027-03"))
        recorded.map { it.window.entityRef } shouldBe listOf("E1")
        published.map { it.first.state } shouldBe listOf(WindowState.LATE)
        published.single().first.part shouldBe WindowPart(day(3, 29), day(3, 31), "2027-03")
        published.single().first.aggregateId shouldBe null
        published
            .single()
            .first.window.entityRef shouldBe "E1"
        store.stored.size shouldBe 1
        outcome.placed.size shouldBe 1
        closedWrites shouldBe emptyList()
        order shouldBe listOf("recordLate", "publish", "store")
        store.stored.map { it.transaction.transactionRef } shouldBe listOf("T0-2027-03-30")
    }

    @Test
    fun `INV-AC-24763 a second transaction in the same closed part publishes no further event`() {
        run("E1", day(3, 30))
        run("E1", day(3, 31))
        recorded.size shouldBe 2
        published.size shouldBe 1
        store.stored.size shouldBe 2
        closedWrites shouldBe emptyList()
        order shouldBe listOf("recordLate", "publish", "store", "recordLate", "store")
    }

    @Test
    fun `INV-AC-24763 a transaction dated inside an open part calls neither recordLate nor the publisher`() {
        run("E1", day(4, 2))
        recorded shouldBe emptyList()
        published shouldBe emptyList()
        store.stored.size shouldBe 1
        closedWrites shouldBe emptyList()
        order shouldBe listOf("store")
    }

    @Test
    fun `INV-AC-24763 the row and the event are written inside the one transaction`() {
        run("E1", day(3, 30))
        recorded.single().inTransaction shouldBe true
        published.single().second shouldBe true
    }

    @Test
    fun `INV-AC-24763 the transaction of another entity records only that entity part`() {
        run("E2", day(3, 30))
        recorded.map { it.window.entityRef } shouldBe listOf("E2")
        published.map { it.first.window.entityRef } shouldBe listOf("E2")
    }

    @Test
    fun `INV-AC-24763 a window whose period split is unknown records nothing`() {
        val held =
            IntakeTransactions(
                { _, _, _ -> Cadence.WEEKLY },
                windows,
                store,
                { _, _, _ -> PeriodSpans.Unknown },
                publisher,
                runner,
            )
        held.execute(
            TransactionsCommand(
                "E1",
                "BU-1",
                AggregateType.POS,
                "K",
                1,
                listOf(DatedTransaction("T", day(3, 30), "SR-STD", "1.00")),
            ),
        )
        recorded shouldBe emptyList()
        published shouldBe emptyList()
    }
}
