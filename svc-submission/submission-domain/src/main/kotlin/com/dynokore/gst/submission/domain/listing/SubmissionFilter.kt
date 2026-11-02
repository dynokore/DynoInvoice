package com.dynokore.gst.submission.domain.listing

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.gst.submission.domain.reconciliation.ReconciliationBucket
import java.math.BigDecimal
import java.net.URLEncoder

/** The nine combinable filters of the submission list (INV-REQ-977); all set members combine with AND. */
data class SubmissionFilter(
    val direction: Direction? = null,
    val submissionType: SubmissionKind? = null,
    val state: LifecycleState? = null,
    val categoryCode: String? = null,
    val counterparty: String? = null,
    val amountMin: BigDecimal? = null,
    val amountMax: BigDecimal? = null,
    val acknowledged: Boolean? = null,
    val bucket: ReconciliationBucket? = null,
) {
    init {
        require(amountMin == null || amountMax == null || amountMin.compareTo(amountMax) <= 0) {
            "amountMin must not exceed amountMax"
        }
        require(categoryCode == null || categoryCode.isNotBlank()) { "categoryCode must not be blank" }
        require(counterparty == null || counterparty.isNotBlank()) { "counterparty must not be blank" }
        require(amountMin == null || amountMin.scale() <= 2) { "amountMin scale must be at most 2" }
        require(amountMax == null || amountMax.scale() <= 2) { "amountMax scale must be at most 2" }
    }

    fun matches(
        row: SubmissionRow,
        bucketOf: (SubmissionRow) -> ReconciliationBucket?,
    ): Boolean =
        matchesAttributes(row) &&
            matchesCounterparty(row) &&
            matchesAmount(row) &&
            matchesStatus(row, bucketOf)

    private fun matchesAttributes(row: SubmissionRow): Boolean =
        (direction == null || row.direction == direction) &&
            (submissionType == null || row.submissionType == submissionType) &&
            (state == null || row.state == state) &&
            (categoryCode == null || categoryCode in row.categoryCodes)

    private fun matchesCounterparty(row: SubmissionRow): Boolean =
        counterparty == null ||
            counterparty == row.counterpartyUen ||
            counterparty.equals(row.counterpartyName, ignoreCase = true)

    private fun matchesAmount(row: SubmissionRow): Boolean =
        (amountMin == null || row.taxableAmount.compareTo(amountMin) >= 0) &&
            (amountMax == null || row.taxableAmount.compareTo(amountMax) <= 0)

    private fun matchesStatus(
        row: SubmissionRow,
        bucketOf: (SubmissionRow) -> ReconciliationBucket?,
    ): Boolean =
        (acknowledged == null || (row.acknowledgedAt != null) == acknowledged) &&
            (bucket == null || bucketOf(row) == bucket)

    /** The matching rows in input order; no row is dropped for any other reason. */
    fun select(
        rows: List<SubmissionRow>,
        bucketOf: (SubmissionRow) -> ReconciliationBucket?,
    ): List<SubmissionRow> = rows.filter { matches(it, bucketOf) }

    /** Order-independent text of the set members, for the export record (INV-REQ-978). */
    fun canonical(): String =
        listOfNotNull(
            direction?.let { "direction=${it.name}" },
            submissionType?.let { "submissionType=${it.name}" },
            state?.let { "state=${it.name}" },
            categoryCode?.let { "categoryCode=${enc(it)}" },
            counterparty?.let { "counterparty=${enc(it)}" },
            amountMin?.let { "amountMin=${it.toPlainString()}" },
            amountMax?.let { "amountMax=${it.toPlainString()}" },
            acknowledged?.let { "acknowledged=$it" },
            bucket?.let { "bucket=${it.name}" },
        ).joinToString("&")

    private fun enc(text: String): String = URLEncoder.encode(text, Charsets.UTF_8)
}

/** Row count and the two exact totals; equals the contract's SubmissionSummaryTotals. */
data class SubmissionTotals(
    val rowCount: Int,
    val totalTaxableAmount: BigDecimal,
    val totalGstAmount: BigDecimal,
) {
    companion object {
        private val ZERO: BigDecimal = BigDecimal("0.00")

        fun of(rows: List<SubmissionRow>): SubmissionTotals =
            SubmissionTotals(
                rows.size,
                rows.fold(ZERO) { acc, r -> acc.add(r.taxableAmount) }.setScale(2),
                rows.fold(ZERO) { acc, r -> acc.add(r.gstAmount) }.setScale(2),
            )
    }
}
