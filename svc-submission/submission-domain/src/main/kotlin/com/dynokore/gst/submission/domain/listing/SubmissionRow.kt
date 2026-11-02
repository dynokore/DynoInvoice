package com.dynokore.gst.submission.domain.listing

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/** Direction of a document for the entity (INV-REQ-975). */
enum class Direction { SALE, PURCHASE }

/** The submission type of a row (INV-REQ-975). */
enum class SubmissionKind { TYPE_1A, TYPE_1B, TYPE_2, TYPE_3 }

/** One row of the submission list: one submitted version, superseded versions included (INV-REQ-975). */
data class SubmissionRow(
    val docUuid: String,
    val version: Int,
    val submissionType: SubmissionKind,
    val state: LifecycleState,
    val dueDate: LocalDate,
    val documentNumber: String,
    val documentTypeCode: String,
    val direction: Direction,
    val counterpartyName: String,
    val counterpartyUen: String,
    val transactionDate: LocalDate,
    val taxableAmount: BigDecimal,
    val gstAmount: BigDecimal,
    val categoryCodes: Set<String>,
    val submittedAt: Instant?,
    val acknowledgedAt: Instant?,
    val acknowledgementId: String?,
) {
    init {
        require(docUuid.isNotBlank()) { "docUuid must not be blank" }
        require(documentNumber.isNotBlank()) { "documentNumber must not be blank" }
        require(version >= 1) { "version must be at least 1" }
        require(documentTypeCode == "380" || documentTypeCode == "381") { "documentTypeCode must be 380 or 381" }
        require(taxableAmount.scale() <= 2) { "taxableAmount scale must be at most 2" }
        require(gstAmount.scale() <= 2) { "gstAmount scale must be at most 2" }
        require((acknowledgedAt == null) == (acknowledgementId == null)) {
            "acknowledgedAt and acknowledgementId must both be present or both absent"
        }
    }

    /** The marker of INV-REQ-975 behavior 2. */
    val superseded: Boolean get() = state == LifecycleState.SUPERSEDED
}

enum class SortField { DUE_DATE, TRANSACTION_DATE }

data class SortKey(
    val field: SortField,
    val descending: Boolean,
) {
    companion object {
        private val PATTERN = Regex("^-?(dueDate|transactionDate)$")

        /** Accepts exactly the contract pattern `^-?(dueDate|transactionDate)$`. */
        fun parse(text: String): SortKey {
            require(PATTERN.matches(text)) { "unsupported sort key" }
            val name = text.removePrefix("-")
            val field = if (name == "dueDate") SortField.DUE_DATE else SortField.TRANSACTION_DATE
            return SortKey(field, text.startsWith("-"))
        }
    }
}

object SubmissionRows {
    val DEFAULT_SORT = SortKey(SortField.TRANSACTION_DATE, false)

    /**
     * Every input row exactly once, ordered by the sort field (reversed when descending); ties by docUuid then
     * version ascending in both directions. The result does not depend on the input order.
     */
    fun assemble(
        rows: List<SubmissionRow>,
        sort: SortKey = DEFAULT_SORT,
    ): List<SubmissionRow> {
        val seen = HashSet<Pair<String, Int>>()
        for (row in rows) {
            require(seen.add(row.docUuid to row.version)) { "duplicate (docUuid, version)" }
        }
        val byField: Comparator<SubmissionRow> =
            when (sort.field) {
                SortField.DUE_DATE -> compareBy { it.dueDate }
                SortField.TRANSACTION_DATE -> compareBy { it.transactionDate }
            }
        val primary = if (sort.descending) byField.reversed() else byField
        return rows.sortedWith(primary.thenBy { it.docUuid }.thenBy { it.version })
    }
}
