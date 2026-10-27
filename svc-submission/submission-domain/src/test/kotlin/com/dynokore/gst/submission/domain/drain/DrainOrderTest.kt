package com.dynokore.gst.submission.domain.drain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.Random

class DrainOrderTest {
    private val drained = DrainScope("A", "2026-Q1")
    private val apr20 = LocalDate.of(2026, 4, 20)
    private val t0 = Instant.parse("2026-04-01T00:00:00Z")

    private fun item(
        doc: String,
        entity: String = "A",
        period: String = "2026-Q1",
        due: LocalDate = LocalDate.of(2026, 4, 30),
        opens: LocalDate = LocalDate.of(2026, 4, 20),
        queued: Instant = t0,
        version: Int = 1,
    ) = DrainItem(doc, version, entity, period, due, opens, queued)

    private fun docs(r: List<RankedItem>) = r.map { it.item.docUuid }

    private fun tierAt(today: LocalDate) = DrainOrder.rank(listOf(item("d")), drained, today).single().tier

    @Test
    fun `INV-AC-173 a document inside its window goes before the drained backlog`() {
        val b = item("b", entity = "B", period = "2026-Q1", due = LocalDate.of(2026, 4, 25), opens = LocalDate.of(2026, 4, 15))
        val a1 = item("a1", opens = LocalDate.of(2026, 4, 25))
        val a2 = item("a2", opens = LocalDate.of(2026, 4, 26))
        val r = DrainOrder.rank(listOf(a1, a2, b), drained, apr20)
        r.first().item shouldBe b
        r.first().tier shouldBe Tier.WINDOW_OPEN
        docs(r) shouldBe listOf("b", "a1", "a2")
        r.drop(1).map { it.tier } shouldBe listOf(Tier.DRAINED_PERIOD, Tier.DRAINED_PERIOD)
    }

    @Test
    fun `INV-AC-208 a routine document outside its window is last after the drained backlog`() {
        val c = item("c", entity = "C", period = "2026-Q2", due = LocalDate.of(2026, 5, 31), opens = LocalDate.of(2026, 5, 21), queued = t0.minusSeconds(3600))
        val a1 = item("a1", opens = LocalDate.of(2026, 4, 25))
        val a2 = item("a2", opens = LocalDate.of(2026, 4, 26), queued = t0.plusSeconds(60))
        val r = DrainOrder.rank(listOf(c, a2, a1), drained, apr20)
        docs(r) shouldBe listOf("a1", "a2", "c")
        r.last().tier shouldBe Tier.ROUTINE
    }

    @Test
    fun `INV-AC-145 drained period queued later goes before earlier queued routine items`() {
        val routine = item("r", period = "2026-Q2", due = LocalDate.of(2026, 5, 10), opens = LocalDate.of(2026, 5, 1), queued = t0)
        val mine = item("m", due = LocalDate.of(2026, 6, 30), opens = LocalDate.of(2026, 6, 20), queued = t0.plusSeconds(500))
        val r = DrainOrder.rank(listOf(routine, mine), drained, apr20)
        docs(r) shouldBe listOf("m", "r")
        r.map { it.tier } shouldBe listOf(Tier.DRAINED_PERIOD, Tier.ROUTINE)
    }

    @Test
    fun `INV-AC-173 today one day before windowOpensOn is not window open`() {
        tierAt(apr20.minusDays(1)) shouldBe Tier.DRAINED_PERIOD
        DrainOrder.rank(listOf(item("d", entity = "B")), drained, apr20.minusDays(1)).single().tier shouldBe Tier.ROUTINE
    }

    @Test
    fun `INV-AC-173 today equal to windowOpensOn is window open`() {
        tierAt(apr20) shouldBe Tier.WINDOW_OPEN
    }

    @Test
    fun `INV-AC-173 today one day after windowOpensOn is window open`() {
        tierAt(apr20.plusDays(1)) shouldBe Tier.WINDOW_OPEN
    }

    private fun closedWindow(
        entity: String,
        period: String,
    ) = item("d", entity = entity, period = period, opens = LocalDate.of(2026, 5, 1))

    @Test
    fun `INV-AC-145 the same period of another entity is routine`() {
        DrainOrder.rank(listOf(closedWindow("B", "2026-Q1")), drained, apr20).single().tier shouldBe Tier.ROUTINE
    }

    @Test
    fun `INV-AC-145 the same entity with another period is routine`() {
        DrainOrder.rank(listOf(closedWindow("A", "2026-Q2")), drained, apr20).single().tier shouldBe Tier.ROUTINE
    }

    @Test
    fun `INV-AC-145 the same entity and period is the drained period`() {
        DrainOrder.rank(listOf(closedWindow("A", "2026-Q1")), drained, apr20).single().tier shouldBe Tier.DRAINED_PERIOD
    }

    @Test
    fun `INV-AC-145 drained absent gives only window open and routine`() {
        val open = item("o")
        val closed = closedWindow("A", "2026-Q1")
        val r = DrainOrder.rank(listOf(closed, open), null, apr20)
        r.map { it.tier } shouldBe listOf(Tier.WINDOW_OPEN, Tier.ROUTINE)
    }

    @Test
    fun `INV-AC-145 window open tier is ordered by due date then queue time not by queue time first`() {
        val late = item("x", due = LocalDate.of(2026, 5, 2), queued = t0)
        val early = item("y", due = LocalDate.of(2026, 4, 25), queued = t0.plusSeconds(10))
        docs(DrainOrder.rank(listOf(late, early), drained, apr20)) shouldBe listOf("y", "x")
    }

    @Test
    fun `INV-AC-145 equal due date is ordered by queuedAt`() {
        val a = item("a", queued = t0.plusSeconds(5))
        val b = item("b", queued = t0)
        docs(DrainOrder.rank(listOf(a, b), drained, apr20)) shouldBe listOf("b", "a")
    }

    @Test
    fun `INV-AC-145 equal queuedAt is ordered by docUuid`() {
        docs(DrainOrder.rank(listOf(item("b"), item("a")), drained, apr20)) shouldBe listOf("a", "b")
    }

    @Test
    fun `INV-AC-145 equal docUuid is ordered by version`() {
        val r = DrainOrder.rank(listOf(item("a", version = 3), item("a", version = 2)), drained, apr20)
        r.map { it.item.version } shouldBe listOf(2, 3)
    }

    @Test
    fun `INV-AC-145 the result is the same for every ordering of the input`() {
        val rnd = Random(20260427)
        val items =
            (0 until 20).map {
                item(
                    "d${it % 7}",
                    entity = if (it % 3 == 0) "B" else "A",
                    period = if (it % 4 == 0) "2026-Q2" else "2026-Q1",
                    due = LocalDate.of(2026, 4, 25 + it % 3),
                    opens = LocalDate.of(2026, 4, 15 + it % 12),
                    queued = t0.plusSeconds((it % 5).toLong()),
                    version = it / 7 + 1,
                )
            }
        val expected = DrainOrder.rank(items, drained, apr20)
        expected.size shouldBe 20
        expected.map { it.item }.toSet() shouldBe items.toSet()
        repeat(50) { DrainOrder.rank(items.shuffled(rnd), drained, apr20) shouldBe expected }
    }

    @Test
    fun `INV-AC-145 two items with one docUuid and version are refused`() {
        shouldThrow<IllegalArgumentException> { DrainOrder.rank(listOf(item("a"), item("a", entity = "B")), drained, apr20) }
    }

    @Test
    fun `INV-AC-145 an empty input gives an empty list`() {
        DrainOrder.rank(emptyList(), drained, apr20) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-145 blank refs and a version below 1 are refused`() {
        shouldThrow<IllegalArgumentException> { item(" ") }
        shouldThrow<IllegalArgumentException> { item("a", entity = "") }
        shouldThrow<IllegalArgumentException> { item("a", period = " ") }
        shouldThrow<IllegalArgumentException> { item("a", version = 0) }
        shouldThrow<IllegalArgumentException> { DrainScope("", "p") }
    }
}
