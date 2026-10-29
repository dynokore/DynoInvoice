package com.dynokore.gst.submission.domain.metrics

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate

class ComplianceMetricsTest {
    private val today = LocalDate.of(2026, 10, 29)
    private var seq = 0

    private val resultProperties =
        setOf(
            "periods",
            "tenant",
            "entityRef",
            "periodRef",
            "coverage",
            "deadline",
            "correction",
            "documentsWithoutSubmission",
            "breached",
            "documentsPastDue",
            "hasResidualBacklog",
            "daysToNearestDeadline",
            "resubmission",
            "creditNote",
            "manyVersionDocuments",
            "numerator",
            "denominator",
            "periodsWithResidualBacklog",
        )

    private fun row(uuid: String = "d${seq++}") =
        ObligationRow(uuid, "E1", "2026-Q3", today.plusDays(10), true, true, RowKind.INVOICE, 1)

    private fun compute(
        rows: List<ObligationRow>,
        from: Int = 3,
    ) = ComplianceMetrics.compute(rows, today, from)

    /** Nine clean entities plus E10 with the given rows. */
    private fun tenEntities(extra: List<ObligationRow>): List<ObligationRow> =
        (1..9).map { row().copy(entityRef = "E$it") } + extra.map { it.copy(entityRef = "E10") }

    private fun pair(
        report: MetricsReport,
        entity: String,
    ) = report.periods.single { it.entityRef == entity }

    @Test
    fun `coverage shows a non-zero value for the entity and period that owns the gap`() {
        val report =
            compute(
                tenEntities(
                    listOf(
                        row().copy(hasSubmission = false),
                        row().copy(inScope = false, hasSubmission = false),
                    ),
                ),
            )
        pair(report, "E10").coverage.documentsWithoutSubmission shouldBe 1
        pair(report, "E10").coverage.breached shouldBe true
        (1..9).forEach {
            pair(report, "E$it").coverage.documentsWithoutSubmission shouldBe 0
            pair(report, "E$it").coverage.breached shouldBe false
        }
    }

    @Test
    fun `INV-AC-2381 deadline metrics show against the late entity and period apart from the tenant total`() {
        val report =
            compute(
                tenEntities(
                    listOf(
                        row().copy(hasSubmission = false, dueDate = today.minusDays(3)),
                        row().copy(hasSubmission = false, dueDate = today.plusDays(5)),
                    ),
                ),
            )
        val late = pair(report, "E10").deadline
        late.documentsPastDue shouldBe 1
        late.hasResidualBacklog shouldBe true
        late.daysToNearestDeadline shouldBe -3L
        report.tenant.documentsPastDue shouldBe 1
        report.tenant.periodsWithResidualBacklog shouldBe 1
        report.tenant.daysToNearestDeadline shouldBe -3L
        (1..9).forEach {
            pair(report, "E$it").deadline shouldBe Deadline(0, false, null)
        }
    }

    @Test
    fun `INV-AC-2095 thirty resubmissions of one hundred invoices give an exact ratio and a data-quality signal`() {
        val rows = (1..100).map { row().copy(versionCount = if (it <= 30) 2 else 1) }
        compute(rows)
            .periods
            .single()
            .correction.resubmission shouldBe Ratio(30, 100)
        ResubmissionSignal.classify(listOf(Ratio(30, 100)), Ratio(20, 100)) shouldBe SignalState.HIGH
        ResubmissionSignal.classify(listOf(Ratio(10, 100), Ratio(30, 100)), Ratio(40, 100)) shouldBe SignalState.RISING
        ResubmissionSignal.classify(listOf(Ratio(30, 100), Ratio(10, 100)), Ratio(40, 100)) shouldBe SignalState.NONE
    }

    @Test
    fun `signal covers both, empty series, zero denominator and the equality edges`() {
        val t = Ratio(20, 100)
        ResubmissionSignal.classify(listOf(Ratio(10, 100), Ratio(30, 100)), t) shouldBe SignalState.HIGH_AND_RISING
        ResubmissionSignal.classify(emptyList(), t) shouldBe SignalState.NONE
        ResubmissionSignal.classify(listOf(Ratio(1, 0)), t) shouldBe SignalState.NONE
        ResubmissionSignal.classify(listOf(Ratio(20, 100)), t) shouldBe SignalState.HIGH
        ResubmissionSignal.classify(listOf(Ratio(10, 100), Ratio(10, 100)), Ratio(50, 100)) shouldBe SignalState.NONE
        // 1/3 against 33/100: integer division would call both 0
        ResubmissionSignal.classify(listOf(Ratio(33, 100)), Ratio(1, 3)) shouldBe SignalState.NONE
        ResubmissionSignal.classify(listOf(Ratio(34, 100)), Ratio(1, 3)) shouldBe SignalState.HIGH
    }

    @Test
    fun `result classes carry no property that holds, blocks, suppresses or rejects`() {
        val classes =
            listOf(
                MetricsReport::class,
                PeriodMetrics::class,
                Coverage::class,
                Deadline::class,
                Correction::class,
                Ratio::class,
                TenantTotals::class,
            )
        val names =
            classes
                .flatMap { c ->
                    c.java.declaredFields
                        .filter { !it.isSynthetic }
                        .map { it.name }
                }.toSet()
        names shouldBe resultProperties
        names.none { n -> listOf("hold", "block", "suppress", "reject").any { n.lowercase().contains(it) } } shouldBe
            true
    }

    @Test
    fun `the same entity in two periods gives two ordered elements and equal periods of two entities give two`() {
        val report =
            compute(
                listOf(
                    row().copy(entityRef = "B", periodRef = "P2"),
                    row().copy(entityRef = "B", periodRef = "P1"),
                    row().copy(entityRef = "A", periodRef = "P2"),
                ),
            )
        report.periods.map { it.entityRef to it.periodRef } shouldBe listOf("A" to "P2", "B" to "P1", "B" to "P2")
    }

    @Test
    fun `a row due exactly today is not past due and is zero days away`() {
        val d = compute(listOf(row().copy(hasSubmission = false, dueDate = today))).periods.single().deadline
        d shouldBe Deadline(0, true, 0L)
    }

    @Test
    fun `many versions counts from the parameter`() {
        compute(listOf(row().copy(versionCount = 3)), from = 3)
            .periods
            .single()
            .correction.manyVersionDocuments shouldBe 1
        compute(listOf(row().copy(versionCount = 2)), from = 3)
            .periods
            .single()
            .correction.manyVersionDocuments shouldBe 0
        compute(listOf(row().copy(versionCount = 3)), from = 4)
            .periods
            .single()
            .correction.manyVersionDocuments shouldBe 0
    }

    @Test
    fun `a credit note is not a resubmission and counts only in the credit note ratio`() {
        val c =
            compute(
                listOf(row(), row().copy(kind = RowKind.CREDIT_NOTE, versionCount = 2)),
            ).periods.single().correction
        c.resubmission shouldBe Ratio(0, 1)
        c.creditNote shouldBe Ratio(1, 2)
    }

    @Test
    fun `an out-of-scope invoice still enters the correction ratios`() {
        val c = compute(listOf(row().copy(inScope = false, versionCount = 2), row())).periods.single().correction
        c.resubmission shouldBe Ratio(1, 2)
    }

    @Test
    fun `an empty list gives no periods and zero totals`() {
        compute(emptyList()) shouldBe MetricsReport(emptyList(), TenantTotals(0, 0, 0, null))
    }

    @Test
    fun `invalid input is refused`() {
        assertThrows<IllegalArgumentException> { row().copy(entityRef = " ") }
        assertThrows<IllegalArgumentException> { row().copy(periodRef = "") }
        assertThrows<IllegalArgumentException> { row(uuid = "") }
        assertThrows<IllegalArgumentException> { row().copy(versionCount = 0) }
        assertThrows<IllegalArgumentException> { compute(listOf(row(uuid = "x"), row(uuid = "x"))) }
        assertThrows<IllegalArgumentException> { compute(emptyList(), from = 1) }
    }

    @Test
    fun `equal input gives equal output`() {
        val rows = listOf(row().copy(hasSubmission = false), row().copy(entityRef = "E2", versionCount = 2))
        compute(rows) shouldBe compute(rows)
    }
}
