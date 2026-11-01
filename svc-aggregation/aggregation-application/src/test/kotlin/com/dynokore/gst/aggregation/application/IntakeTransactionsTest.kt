package com.dynokore.gst.aggregation.application

import com.dynokore.gst.aggregation.domain.AggregateType
import com.dynokore.gst.aggregation.domain.AggregationWindow
import com.dynokore.gst.aggregation.domain.Cadence
import com.dynokore.gst.aggregation.domain.WindowState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class IntakeTransactionsTest {
    private class Windows : WindowRepository {
        val placed = mutableListOf<LocalDate>()

        override fun place(
            entityRef: String,
            aggregateType: AggregateType,
            date: LocalDate,
            cadence: Cadence,
        ): Placement {
            placed += date
            return Placement(AggregationWindow.containing(cadence, date), WindowState.OPEN)
        }

        override fun openWindowsEndingOnOrBefore(date: LocalDate) = emptyList<OpenWindow>()

        override fun closePart(
            window: OpenWindow,
            part: com.dynokore.gst.aggregation.domain.WindowPart,
            aggregateId: java.util.UUID,
        ) = Unit

        override fun recordLate(window: OpenWindow) = Unit
    }

    private class Store : TransactionRepository {
        val rows = mutableMapOf<Triple<String, String, Long>, List<PlacedTransaction>>()
        var stores = 0

        override fun find(
            entityRef: String,
            sourceKey: String,
            sourceRevision: Long,
        ) = rows[Triple(entityRef, sourceKey, sourceRevision)]

        override fun store(
            entityRef: String,
            sourceKey: String,
            sourceRevision: Long,
            placed: List<PlacedTransaction>,
        ) {
            stores++
            rows[Triple(entityRef, sourceKey, sourceRevision)] = placed
        }
    }

    private val windows = Windows()
    private val store = Store()
    private val intake = IntakeTransactions({ _, type, _ -> Cadence.defaultFor(type) }, windows, store)

    private fun tx(
        ref: String,
        date: LocalDate,
    ) = DatedTransaction(ref, date, "SR-STD", "10.00")

    private fun command(vararg transactions: DatedTransaction) =
        TransactionsCommand("ent-1", "bu-1", AggregateType.POS, "K", 1, transactions.toList())

    @Test
    fun `each transaction is stored in the window of its transaction date not of the intake day`() {
        val outcome = intake.execute(command(tx("T1", LocalDate.of(2027, 3, 31)), tx("T2", LocalDate.of(2027, 4, 1))))
        val expected = listOf(LocalDate.of(2027, 3, 31), LocalDate.of(2027, 4, 1))
        outcome.placed.map { it.placement.window.start } shouldBe expected
        windows.placed shouldBe listOf(LocalDate.of(2027, 3, 31), LocalDate.of(2027, 4, 1))
    }

    @Test
    fun `a weekly cadence puts the transactions of one week in one window`() {
        val weekly = IntakeTransactions({ _, _, _ -> Cadence.WEEKLY }, windows, store)
        val outcome = weekly.execute(command(tx("T1", LocalDate.of(2027, 4, 5)), tx("T2", LocalDate.of(2027, 4, 11))))
        outcome.placed
            .map { it.placement.window }
            .distinct()
            .size shouldBe 1
    }

    @Test
    fun `a repeat of the intake key replays the original result and stores nothing again`() {
        val first = intake.execute(command(tx("T1", LocalDate.of(2027, 3, 31))))
        val repeat = intake.execute(command(tx("T9", LocalDate.of(2027, 4, 9))))
        (first is TransactionsOutcome.Created) shouldBe true
        (repeat is TransactionsOutcome.Replayed) shouldBe true
        repeat.placed shouldBe first.placed
        store.stores shouldBe 1
        windows.placed.size shouldBe 1
    }

    @Test
    fun `another revision of the same source key is a new intake`() {
        intake.execute(command(tx("T1", LocalDate.of(2027, 3, 31))))
        val second = intake.execute(command(tx("T1", LocalDate.of(2027, 3, 31))).copy(sourceRevision = 2))
        (second is TransactionsOutcome.Created) shouldBe true
    }
}
