package com.dynokore.gst.reconciliation.domain.scale

import com.dynokore.gst.reconciliation.domain.boxes.Basis
import com.dynokore.gst.reconciliation.domain.boxes.BoxFeed
import com.dynokore.gst.reconciliation.domain.boxes.Direction
import com.dynokore.gst.reconciliation.domain.boxes.MappingEntry
import com.dynokore.gst.reconciliation.domain.run.ReconciliationRunner
import com.dynokore.gst.reconciliation.domain.run.ReturnFigures
import com.dynokore.gst.reconciliation.domain.run.RunDocument
import com.dynokore.gst.reconciliation.domain.run.RunDocumentVersion
import com.dynokore.gst.reconciliation.domain.run.RunOutcome
import com.dynokore.gst.reconciliation.domain.sgd.SgdAllocation
import com.dynokore.gst.reconciliation.domain.sgd.SgdLine
import com.dynokore.gst.reconciliation.domain.variance.DocumentFacts
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import java.math.BigDecimal
import java.time.Duration

/** INV-REQ-1362 / INV-AC-2327: the submitted side, the engine call and the result for 1M documents of 12 months. */
class RunScaleTest {
    private companion object {
        // INV-REQ-1362: the owner's published figure of 5 minutes for 1M documents over 12 months; not a tunable.
        val BUDGET: Duration = Duration.ofMinutes(5)

        const val DOCUMENTS = 1_000_000
        const val MONTHS = 12
        const val YEAR = 2025
        const val COUNT_BOX = "COUNT"
        val CODES = listOf("SR", "ES", "ZR")
        val CODE_BOX = mapOf("SR" to "1", "ES" to "3", "ZR" to "2")
        val ONE_CENT: BigDecimal = BigDecimal("0.01")
    }

    private val entries =
        CODES.map { code ->
            MappingEntry(
                code,
                Direction.SALE,
                listOf(BoxFeed(CODE_BOX.getValue(code), Basis.TAXABLE_AMOUNT), BoxFeed(COUNT_BOX, Basis.TAX_AMOUNT)),
            )
        }

    private fun month(i: Int) = i % MONTHS

    private fun code(i: Int) = CODES[i % CODES.size]

    // Cents of a document's version; versions differ so that choosing the wrong version changes the total.
    private fun cents(
        i: Int,
        version: Int,
    ): Long = (i % 997 + 1).toLong() * 100 + (version - 1) * 100L + i % 89

    private fun hasV2(i: Int) = i % 10 == 0

    private fun hasV3(i: Int) = i % 100 == 0

    private fun versionOf(
        i: Int,
        version: Int,
        acknowledged: Boolean,
    ): RunDocumentVersion {
        val line = SgdLine(1, code(i), BigDecimal.valueOf(cents(i, version), 2), ONE_CENT)
        return RunDocumentVersion(
            version,
            acknowledged,
            DocumentFacts(null, null),
            Direction.SALE,
            SgdAllocation.Allocated(listOf(line)),
        )
    }

    private fun build(count: Int): List<List<RunDocument>> {
        val perMonth = List(MONTHS) { ArrayList<RunDocument>(count / MONTHS + 1) }
        for (i in 0 until count) {
            val versions = ArrayList<RunDocumentVersion>(3)
            versions += versionOf(i, 1, true)
            if (hasV2(i)) versions += versionOf(i, 2, true)
            if (hasV3(i)) versions += versionOf(i, 3, false)
            perMonth[month(i)] += RunDocument("doc-$YEAR-$i", versions)
        }
        return perMonth
    }

    /** Independent totals: per Box and per month, from the formulas only, by a separate loop. */
    private fun expected(): Map<Int?, Map<String, BigDecimal>> {
        val cents = Array(MONTHS) { mutableMapOf<String, Long>() }
        val lines = LongArray(MONTHS)
        for (i in 0 until DOCUMENTS) {
            val highestAcknowledged = if (hasV2(i)) 2 else 1
            cents[month(i)].merge(CODE_BOX.getValue(code(i)), cents(i, highestAcknowledged), Long::plus)
            lines[month(i)]++
        }
        val byMonth =
            (0 until MONTHS).associate { m ->
                m to
                    (
                        cents[m].mapValues { BigDecimal.valueOf(it.value, 2) } +
                            (COUNT_BOX to ONE_CENT.multiply(BigDecimal(lines[m])))
                    )
            }
        val year =
            byMonth.values
                .flatMap { it.entries }
                .groupBy({ it.key }, { it.value })
                .mapValues { (_, v) -> v.fold(BigDecimal.ZERO.setScale(2), BigDecimal::add) }
        return byMonth + (null to year)
    }

    @Test
    fun `INV-AC-2327 a full-period run over one million documents of twelve months ends inside five minutes`() {
        val perMonth = build(DOCUMENTS)
        perMonth.size shouldBe MONTHS
        perMonth.forEach { check(it.size >= 83_000) { "month holds ${it.size} documents" } }
        val expected = expected()
        val periods: List<Pair<Int?, List<RunDocument>>> =
            perMonth.mapIndexed { m, docs -> m to docs } + (null to perMonth.flatten())

        val started = System.nanoTime()
        val outcomes =
            assertTimeoutPreemptively(
                BUDGET,
                ThrowingSupplier {
                    periods.map { (period, docs) ->
                        val total = expected.getValue(period)
                        val figures = ReturnFigures(1, total)
                        period to
                            ReconciliationRunner.run(figures, entries, docs).shouldBeInstanceOf<RunOutcome.Completed>()
                    }
                },
            )
        println("RunScaleTest elapsed ms: ${(System.nanoTime() - started) / 1_000_000}")

        outcomes.size shouldBe MONTHS + 1
        outcomes.count { it.first != null } shouldBe MONTHS
        var folded = 0L
        for ((period, outcome) in outcomes) {
            outcome.unmappedLines shouldBe emptyList()
            val submitted = outcome.result.comparison.associate { it.key to it.submittedAmount }
            // The Box COUNT takes 0.01 per folded line, so its total is the number of lines the fold took.
            if (period != null) folded += submitted.getValue(COUNT_BOX).movePointRight(2).toLong()
            val want = expected.getValue(period)
            submitted.keys shouldBe want.keys
            want.forEach { (box, total) -> submitted.getValue(box) shouldBe total }
        }
        folded shouldBe DOCUMENTS.toLong()
    }
}
