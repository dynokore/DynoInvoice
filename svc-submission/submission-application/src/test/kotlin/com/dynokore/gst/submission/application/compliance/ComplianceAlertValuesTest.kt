package com.dynokore.gst.submission.application.compliance

import com.dynokore.gst.submission.domain.metrics.ComplianceMetrics
import com.dynokore.gst.submission.domain.metrics.ObligationRow
import com.dynokore.gst.submission.domain.metrics.RowKind
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

class ComplianceAlertValuesTest {
    private val today = LocalDate.of(2026, 10, 30)

    private fun row(
        id: String,
        entity: String,
        due: LocalDate,
        submitted: Boolean,
    ) = ObligationRow(id, entity, "2026-Q1", due, true, submitted, RowKind.INVOICE, 1)

    private fun derive(vararg rows: ObligationRow) =
        ComplianceAlertValues.derive(ComplianceMetrics.compute(rows.toList(), today, 3), today)

    @Test
    fun `INV-AC-2094 a pair with two documents without submission gives one value with the same count and days`() {
        val values =
            derive(
                row("a", "e1", today.plusDays(3), false),
                row("b", "e1", today.plusDays(9), false),
                row("c", "e1", today.plusDays(1), true),
            )
        values shouldBe listOf(AlertValue("e1", "2026-Q1", today, 2, 0, 3))
    }

    @Test
    fun `INV-AC-12837 a pair with zero documents without submission gives no value`() {
        derive(row("a", "e1", today.plusDays(3), true)) shouldBe emptyList()
    }

    @Test
    fun `INV-AC-12837 only the non-zero pair of ten entities gives a value`() {
        val rows = (1..10).map { row("d$it", "e$it", today.plusDays(3), submitted = it != 4) }
        derive(*rows.toTypedArray()) shouldBe listOf(AlertValue("e4", "2026-Q1", today, 1, 0, 3))
    }

    @Test
    fun `INV-AC-12837 a past-due pair gives a negative days value unchanged`() {
        derive(row("a", "e1", today.minusDays(5), false)) shouldBe
            listOf(AlertValue("e1", "2026-Q1", today, 1, 1, -5))
    }

    @Test
    fun `INV-AC-12837 the value is not compared with any threshold so a far deadline still gives a value`() {
        derive(row("a", "e1", today.plusDays(4000), false)) shouldBe
            listOf(AlertValue("e1", "2026-Q1", today, 1, 0, 4000))
    }
}
