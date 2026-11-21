package com.dynokore.gst.submission.domain.readiness

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.ordering.LifecycleState.ACKNOWLEDGED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.BLOCKED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.NOT_SUBMITTABLE
import com.dynokore.gst.submission.domain.ordering.LifecycleState.QUEUED
import com.dynokore.gst.submission.domain.ordering.LifecycleState.REJECTED
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ReadinessCountsByStateTest {
    private val day = LocalDate.of(2026, 10, 15)
    private var seq = 0

    private fun entry(
        vararg versions: LifecycleState,
        date: LocalDate = day,
    ) = ReadinessEntry("doc-" + (seq++), versions.toList(), date, false)

    private fun inScope(
        entries: List<ReadinessEntry>,
        start: LocalDate?,
    ) = when (val r = ReadinessCounter.evaluate(entries, start, true)) {
        is ReadinessResult.Ready -> r.counts.inScope
        is ReadinessResult.Blocked -> r.counts.inScope
        ReadinessResult.UnableToDetermine -> error("no counts")
    }

    @Test
    fun `INV-AC-26671 states are counted in lifecycle order without NOT_SUBMITTABLE and sum to inScope`() {
        val entries =
            listOf(
                entry(QUEUED),
                entry(BLOCKED),
                entry(NOT_SUBMITTABLE),
                entry(QUEUED),
                entry(ACKNOWLEDGED),
            )
        val map = ReadinessCounter.countsByState(entries, null)
        map shouldBe mapOf(ACKNOWLEDGED to 1, QUEUED to 2, BLOCKED to 1)
        map.keys.toList() shouldBe listOf(BLOCKED, QUEUED, ACKNOWLEDGED)
        map.values.sum() shouldBe inScope(entries, null)
    }

    @Test
    fun `INV-AC-26671 an entry before the obligation start date is removed from the map and from inScope`() {
        val start = day.plusDays(1)
        val entries = listOf(entry(QUEUED, date = day), entry(QUEUED, date = start), entry(BLOCKED, date = start))
        val map = ReadinessCounter.countsByState(entries, start)
        map shouldBe mapOf(QUEUED to 1, BLOCKED to 1)
        map.values.sum() shouldBe inScope(entries, start)
        inScope(entries, start) shouldBe 2
    }

    @Test
    fun `INV-AC-26671 a rejected latest version after an acknowledged one is counted under REJECTED`() {
        val entries = listOf(entry(ACKNOWLEDGED, REJECTED))
        ReadinessCounter.countsByState(entries, null) shouldBe mapOf(REJECTED to 1)
    }

    @Test
    fun `INV-AC-26671 an empty entry list gives an empty map`() {
        ReadinessCounter.countsByState(emptyList(), null) shouldBe emptyMap()
        ReadinessCounter.countsByState(emptyList(), day) shouldBe emptyMap()
    }
}
