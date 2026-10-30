package com.dynokore.gst.submission.domain.metrics

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.ordering.LifecycleState.ACKNOWLEDGED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.DEAD_LETTERED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.QUEUED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.REJECTED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.RETRY
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SENT
import com.dynokore.gst.submission.domain.ordering.LifecycleState.SENT_UNCONFIRMED
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class BacklogMetricsTest {
    private val now = Instant.parse("2026-10-30T12:00:00Z")
    private val today = LocalDate.of(2026, 10, 30)
    private val due = LocalDate.of(2026, 10, 1)
    private var seq = 0

    private fun v(
        state: LifecycleState,
        entity: String = "E-001",
        period: String = "2026-Q3",
        queuedAt: Instant = now.minus(Duration.ofHours(1)),
        dueDate: LocalDate = due,
    ) = BacklogVersion("doc-${seq++}", 1, entity, period, state, queuedAt, dueDate)

    private fun compute(vararg vs: BacklogVersion) = BacklogMetrics.compute(vs.toList(), now, today)

    private fun mixed(): List<BacklogVersion> =
        List(3) { v(QUEUED) } +
            v(RETRY, queuedAt = now.minus(Duration.ofHours(6))) +
            v(DEAD_LETTERED) +
            List(2) { v(SENT_UNCONFIRMED) } +
            List(3) { v(SENT) } +
            v(REJECTED)

    @Test
    fun `INV-AC-13135 values of one entity and period`() {
        val r = BacklogMetrics.compute(mixed(), now, today).single()
        r.queueDepth shouldBe 4
        r.oldestUnsentAge shouldBe Duration.ofHours(6)
        r.failureRate shouldBe Ratio(2, 7)
        r.unacknowledgedCount shouldBe 2
        r.unsent.size shouldBe 5
    }

    @Test
    fun `INV-AC-13135 failure rate is exact and not reduced`() {
        compute(v(REJECTED), v(DEAD_LETTERED), v(SENT), v(ACKNOWLEDGED)).single().failureRate shouldBe Ratio(2, 4)
    }

    @Test
    fun `INV-AC-13135 dead-lettered stays unsent and oldest counts it`() {
        val r = compute(v(DEAD_LETTERED, queuedAt = now.minus(Duration.ofHours(9))), v(QUEUED)).single()
        r.oldestUnsentAge shouldBe Duration.ofHours(9)
        r.queueDepth shouldBe 1
    }

    @Test
    fun `INV-AC-13135 retry is not unacknowledged`() {
        compute(v(RETRY), v(SENT_UNCONFIRMED), v(ACKNOWLEDGED)).single().unacknowledgedCount shouldBe 1
    }

    @Test
    fun `INV-AC-13135 separate entries per entity and per period`() {
        val twoEntities = compute(v(QUEUED, entity = "E-002"), v(QUEUED, entity = "E-001"))
        twoEntities.map { it.entityRef } shouldBe listOf("E-001", "E-002")
        val twoPeriods = compute(v(QUEUED, period = "2026-Q4"), v(QUEUED, period = "2026-Q3"))
        twoPeriods.map { it.periodRef } shouldBe listOf("2026-Q3", "2026-Q4")
        twoPeriods.map { it.queueDepth } shouldBe listOf(1, 1)
    }

    @Test
    fun `INV-AC-13134 outage length changes neither due date nor late flag`() {
        fun item(age: Duration) = compute(v(QUEUED, queuedAt = now.minus(age), dueDate = due)).single().unsent.single()
        val short = item(Duration.ofHours(1))
        val long = item(Duration.ofDays(90))
        short.dueDate shouldBe long.dueDate
        short.formallyLate shouldBe long.formallyLate
        short.dueDate shouldBe due
        short.formallyLate shouldBe true
    }

    @Test
    fun `due date equal to today is not late and one day before is late`() {
        val items = compute(v(QUEUED, dueDate = today), v(QUEUED, dueDate = today.minusDays(1))).single().unsent
        items.map { it.formallyLate } shouldBe listOf(true, false)
        items.map { it.dueDate } shouldBe listOf(today.minusDays(1), today)
    }

    @Test
    fun `no unsent version gives null age`() {
        compute(v(SENT)).single().oldestUnsentAge shouldBe null
    }

    @Test
    fun `no completed version gives zero over zero`() {
        compute(v(QUEUED)).single().failureRate shouldBe Ratio(0, 0)
    }

    @Test
    fun `queuedAt after now gives age zero`() {
        compute(v(QUEUED, queuedAt = now.plusSeconds(60))).single().oldestUnsentAge shouldBe Duration.ZERO
    }

    @Test
    fun `look-alike entity with trailing space is a separate group`() {
        compute(v(QUEUED), v(QUEUED, entity = "E-001 ")).size shouldBe 2
    }

    @Test
    fun `duplicate docUuid and version is refused`() {
        assertThrows<IllegalArgumentException> { compute(v(QUEUED).copy(docUuid = "x"), v(SENT).copy(docUuid = "x")) }
        compute(v(QUEUED).copy(docUuid = "x"), v(SENT).copy(docUuid = "x", version = 2)).size shouldBe 1
    }

    @Test
    fun `blank entity is refused`() {
        assertThrows<IllegalArgumentException> { v(QUEUED, entity = " ") }
    }
}
