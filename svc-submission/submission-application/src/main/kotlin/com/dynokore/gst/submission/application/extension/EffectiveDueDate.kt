package com.dynokore.gst.submission.application.extension

import java.time.LocalDate

/**
 * One recorded extension as the resolver reads it (INV-REQ-81). [target] and [scopeKind] are the stored texts so that
 * a value outside the published sets is reported, never guessed. [extendedDueDate] is the submission due date unless
 * the target is FILING_DUE_DATE, which the resolver ignores.
 */
data class ExtensionInput(
    val target: String,
    val scopeKind: String,
    val scopeRef: String?,
    val extendedDueDate: LocalDate,
)

/** The effective due date of a document and the rows that could not be ordered (reported, never matched). */
data class EffectiveDueDateResult(
    val dueDate: LocalDate,
    val unorderable: List<ExtensionInput>,
)

/**
 * The effective submission due date of a document (INV-REQ-81, INV-REQ-70): the extended date of the row of the
 * narrowest matching scope (DOCUMENT_SET, then DATE_RANGE, then PERIOD), among equal scopes the latest recorded row,
 * else the register due date. Pure: no clock, no SQL, no framework. Lateness never moves a date.
 */
object EffectiveDueDate {
    private const val DOCUMENT_SET = "DOCUMENT_SET"
    private const val DATE_RANGE = "DATE_RANGE"
    private const val PERIOD = "PERIOD"
    private val SUBMISSION_TARGETS = setOf("SUBMISSION_DUE_DATE", "BOTH")
    private const val FILING_ONLY = "FILING_DUE_DATE"

    /** [rows] are in order of recording, oldest first; [transactionDate] is null when the document has none. */
    fun resolve(
        registerDueDate: LocalDate,
        docUuid: String,
        transactionDate: LocalDate?,
        rows: List<ExtensionInput>,
    ): EffectiveDueDateResult {
        val unorderable = mutableListOf<ExtensionInput>()
        val matched = mutableListOf<Pair<Int, ExtensionInput>>()
        for (row in rows) {
            if (row.target == FILING_ONLY) continue
            val rank = if (row.target in SUBMISSION_TARGETS) rank(row, docUuid, transactionDate) else UNORDERABLE
            if (rank == UNORDERABLE) {
                unorderable += row
            } else if (rank != NO_MATCH) {
                matched += rank to row
            }
        }
        val best = matched.maxOfOrNull { it.first }
        val chosen = matched.lastOrNull { it.first == best }?.second
        return EffectiveDueDateResult(chosen?.extendedDueDate ?: registerDueDate, unorderable)
    }

    /** Higher is narrower; [NO_MATCH] when the row does not match this document; [UNORDERABLE] when unreadable. */
    private fun rank(
        row: ExtensionInput,
        docUuid: String,
        transactionDate: LocalDate?,
    ): Int =
        when (row.scopeKind) {
            PERIOD -> if (row.scopeRef == null) RANK_PERIOD else UNORDERABLE
            DOCUMENT_SET -> documentSet(row.scopeRef, docUuid)
            DATE_RANGE -> dateRange(row.scopeRef)?.let { inRange(it, transactionDate) } ?: UNORDERABLE
            else -> UNORDERABLE
        }

    private fun inRange(
        range: ClosedRange<LocalDate>,
        transactionDate: LocalDate?,
    ): Int = if (transactionDate != null && transactionDate in range) RANK_RANGE else NO_MATCH

    private fun documentSet(
        ref: String?,
        docUuid: String,
    ): Int =
        when {
            ref == null -> UNORDERABLE
            docUuid.lowercase() in ref.lowercase().split(',') -> RANK_SET
            else -> NO_MATCH
        }

    private fun dateRange(ref: String?): ClosedRange<LocalDate>? {
        val parts = ref?.split('/').orEmpty()
        return if (parts.size == RANGE_PARTS) {
            runCatching { LocalDate.parse(parts[0])..LocalDate.parse(parts[1]) }.getOrNull()
        } else {
            null
        }
    }

    private const val RANGE_PARTS = 2
    private const val RANK_PERIOD = 1
    private const val RANK_RANGE = 2
    private const val RANK_SET = 3
    private const val NO_MATCH = -1
    private const val UNORDERABLE = -2
}
