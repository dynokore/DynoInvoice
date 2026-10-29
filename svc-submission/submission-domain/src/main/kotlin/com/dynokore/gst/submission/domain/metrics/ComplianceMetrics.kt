package com.dynokore.gst.submission.domain.metrics

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Kind of an obligation row; a credit note is a separate document, never a resubmission (INV-REQ-544). */
enum class RowKind { INVOICE, CREDIT_NOTE }

/** One document of the obligation register, as the metrics see it (INV-REQ-1069, INV-REQ-1070). */
data class ObligationRow(
    val docUuid: String,
    val entityRef: String,
    val periodRef: String,
    val dueDate: LocalDate,
    val inScope: Boolean,
    val hasSubmission: Boolean,
    val kind: RowKind,
    val versionCount: Int,
) {
    init {
        require(docUuid.isNotBlank()) { "docUuid must not be blank" }
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(periodRef.isNotBlank()) { "periodRef must not be blank" }
        require(versionCount >= 1) { "versionCount must be at least 1" }
    }
}

/** Exact ratio of two non-negative integers; no rounding, no percentage. */
data class Ratio(
    val numerator: Int,
    val denominator: Int,
) {
    init {
        require(numerator >= 0) { "numerator must not be negative" }
        require(denominator >= 0) { "denominator must not be negative" }
    }
}

data class Coverage(
    val documentsWithoutSubmission: Int,
    val breached: Boolean,
)

data class Deadline(
    val documentsPastDue: Int,
    val hasResidualBacklog: Boolean,
    val daysToNearestDeadline: Long?,
)

data class Correction(
    val resubmission: Ratio,
    val creditNote: Ratio,
    val manyVersionDocuments: Int,
)

data class PeriodMetrics(
    val entityRef: String,
    val periodRef: String,
    val coverage: Coverage,
    val deadline: Deadline,
    val correction: Correction,
)

data class TenantTotals(
    val documentsWithoutSubmission: Int,
    val documentsPastDue: Int,
    val periodsWithResidualBacklog: Int,
    val daysToNearestDeadline: Long?,
)

data class MetricsReport(
    val periods: List<PeriodMetrics>,
    val tenant: TenantTotals,
)

/**
 * Coverage, deadline and correction metrics per entity and prescribed period (INV-REQ-1069, INV-REQ-1070).
 * Pure: `today` is a parameter and the tenant totals sit beside the per-period values, never in place of them.
 */
object ComplianceMetrics {
    fun compute(
        rows: List<ObligationRow>,
        today: LocalDate,
        manyVersionsFrom: Int,
    ): MetricsReport {
        require(manyVersionsFrom >= 2) { "manyVersionsFrom must be at least 2" }
        val seen = HashSet<String>()
        rows.forEach { require(seen.add(it.docUuid)) { "repeated docUuid ${it.docUuid}" } }

        val periods =
            rows
                .groupBy { it.entityRef to it.periodRef }
                .toSortedMap(compareBy<Pair<String, String>>({ it.first }, { it.second }))
                .map { (key, group) -> periodOf(key.first, key.second, group, today, manyVersionsFrom) }
        val tenant =
            TenantTotals(
                documentsWithoutSubmission = periods.sumOf { it.coverage.documentsWithoutSubmission },
                documentsPastDue = periods.sumOf { it.deadline.documentsPastDue },
                periodsWithResidualBacklog = periods.count { it.deadline.hasResidualBacklog },
                daysToNearestDeadline = periods.mapNotNull { it.deadline.daysToNearestDeadline }.minOrNull(),
            )
        return MetricsReport(periods, tenant)
    }

    private fun periodOf(
        entityRef: String,
        periodRef: String,
        rows: List<ObligationRow>,
        today: LocalDate,
        manyVersionsFrom: Int,
    ): PeriodMetrics {
        val open = rows.filter { it.inScope && !it.hasSubmission }
        val invoices = rows.filter { it.kind == RowKind.INVOICE }
        return PeriodMetrics(
            entityRef = entityRef,
            periodRef = periodRef,
            coverage = Coverage(open.size, open.isNotEmpty()),
            deadline =
                Deadline(
                    documentsPastDue = open.count { it.dueDate.isBefore(today) },
                    hasResidualBacklog = open.isNotEmpty(),
                    daysToNearestDeadline = open.minOfOrNull { it.dueDate }?.let { ChronoUnit.DAYS.between(today, it) },
                ),
            correction =
                Correction(
                    resubmission = Ratio(invoices.count { it.versionCount >= 2 }, invoices.size),
                    creditNote = Ratio(rows.count { it.kind == RowKind.CREDIT_NOTE }, rows.size),
                    manyVersionDocuments = rows.count { it.versionCount >= manyVersionsFrom },
                ),
        )
    }
}

/** Where the resubmission-rate trend stands; an upstream data-quality signal, never a fault (INV-REQ-1070). */
enum class SignalState { NONE, HIGH, RISING, HIGH_AND_RISING }

object ResubmissionSignal {
    /** `series` is ordered oldest to newest; ratios are compared by cross multiplication only. */
    fun classify(
        series: List<Ratio>,
        highAtOrAbove: Ratio,
    ): SignalState {
        val last = series.lastOrNull()?.takeIf { it.denominator != 0 } ?: return SignalState.NONE
        val high = highAtOrAbove.denominator != 0 && atLeast(last, highAtOrAbove)
        val before = series.getOrNull(series.size - 2)?.takeIf { it.denominator != 0 }
        val rising = before != null && atLeast(last, before) && !atLeast(before, last)
        return when {
            high && rising -> SignalState.HIGH_AND_RISING
            high -> SignalState.HIGH
            rising -> SignalState.RISING
            else -> SignalState.NONE
        }
    }

    private fun atLeast(
        a: Ratio,
        b: Ratio,
    ): Boolean = a.numerator.toLong() * b.denominator >= b.numerator.toLong() * a.denominator
}
