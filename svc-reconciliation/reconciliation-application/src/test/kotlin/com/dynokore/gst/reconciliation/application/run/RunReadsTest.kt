package com.dynokore.gst.reconciliation.application.run

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class RunReadsTest {
    private val old = RecordedRun("R-old", "E1", "P1", 1, Instant.parse("2026-04-01T00:00:00Z"), "F7")
    private val new = RecordedRun("R-new", "E1", "P1", 2, Instant.parse("2026-04-02T00:00:00Z"), "F5")
    private val other = RecordedRun("R-x", "E1", "P1", 1, Instant.parse("2026-04-03T00:00:00Z"), "F5")
    private val box =
        RunBox("1", BigDecimal("100.00"), BigDecimal("90.00"), BigDecimal("10.00"), emptyList(), BigDecimal.ZERO)

    private fun snap(run: RecordedRun) = RunSnapshot(run, listOf(box), BigDecimal.ZERO, emptyList(), false)

    private val store =
        mapOf("T1" to listOf(old, new), "T2" to listOf(other))

    private val reader =
        object : RunReader {
            var lastLimit = -1
            var finds = 0

            override fun latest(
                tenant: String,
                entityRef: String,
                periodRef: String,
            ): RunSnapshot? = page(tenant, entityRef, periodRef, 1, null).firstOrNull()?.let(::snap)

            override fun page(
                tenant: String,
                entityRef: String,
                periodRef: String,
                limit: Int,
                after: RunCursor?,
            ): List<RecordedRun> {
                lastLimit = limit
                return store[tenant]
                    .orEmpty()
                    .filter { it.entityRef == entityRef && it.periodRef == periodRef }
                    .sortedWith(compareByDescending<RecordedRun> { it.completedAt }.thenByDescending { it.runId })
                    .filter { after == null || isBefore(it, after) }
                    .take(limit)
            }

            override fun find(
                tenant: String,
                runId: String,
            ): RunSnapshot? {
                finds++
                return store[tenant]?.firstOrNull { it.runId == runId }?.let(::snap)
            }
        }

    private fun isBefore(
        run: RecordedRun,
        after: RunCursor,
    ) = run.completedAt < after.completedAt || (run.completedAt == after.completedAt && run.runId < after.runId)

    private val list = ListRuns(reader)
    private val get = GetRun(reader)

    @Test
    fun `INV-AC-24399 limit 1 returns newest first then the older run with no cursor`() {
        val first = list.execute("T1", "E1", "P1", 1, null)
        first.items shouldBe listOf(new)
        val cursor = first.nextCursor!!
        val second = list.execute("T1", "E1", "P1", 1, cursor)
        second.items shouldBe listOf(old)
        second.nextCursor shouldBe null
        reader.lastLimit shouldBe 2
    }

    @Test
    fun `INV-AC-24661 list items carry the form of their own run and find is never called`() {
        val items = list.execute("T1", "E1", "P1", null, null).items
        items.map { it.runId to it.returnForm } shouldBe listOf("R-new" to "F5", "R-old" to "F7")
        reader.finds shouldBe 0
    }

    @Test
    fun `INV-AC-24399 a period without runs returns no item and no cursor`() {
        val page = list.execute("T1", "E1", "P2", null, null)
        page.items.shouldBeEmpty()
        page.nextCursor shouldBe null
    }

    @Test
    fun `INV-AC-24399 default limit is 100 and a tenant reads only its own data`() {
        list.execute("T2", "E1", "P1", null, null).items shouldBe listOf(other)
        reader.lastLimit shouldBe 101
        list.execute("T1", "E1", "P1", null, null).items shouldBe listOf(new, old)
    }

    @Test
    fun `INV-AC-24399 limit 501 limit 0 and an unreadable cursor are refused never clamped`() {
        shouldThrow<IllegalArgumentException> { list.execute("T1", "E1", "P1", 501, null) }
        shouldThrow<IllegalArgumentException> { list.execute("T1", "E1", "P1", 0, null) }
        shouldThrow<IllegalArgumentException> { list.execute("T1", "E1", "P1", 1, "!!not-a-cursor") }
        shouldThrow<IllegalArgumentException> { list.execute("T1", "E1", "P1", 1, "bm8tc2VwYXJhdG9y") }
        list.execute("T1", "E1", "P1", 500, null).items.size shouldBe 2
    }

    @Test
    fun `INV-AC-24399 GetRun reads R-old with its boxes and hides other tenants and unknown ids`() {
        get.execute("T1", "R-old") shouldBe snap(old)
        get.execute("T1", "R-old")!!.boxes shouldBe listOf(box)
        get.execute("T1", "R-x") shouldBe null
        get.execute("T1", "R-unknown") shouldBe null
    }
}
