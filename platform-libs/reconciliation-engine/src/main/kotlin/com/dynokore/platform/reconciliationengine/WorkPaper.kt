package com.dynokore.platform.reconciliationengine

import java.time.Instant

/** The header keys the host requires on its work-paper. */
data class WorkPaperSpec(
    val requiredHeaderKeys: Set<String>,
)

data class AttestationEntry(
    val role: String,
    val person: String,
    val timestamp: Instant,
)

/** A host header key equal to a key the work-paper reserves for itself. */
class ReservedHeaderKeyException(
    val key: String,
) : IllegalArgumentException("Header key '$key' is reserved by the work-paper")

/** One exported row: the section name and the row cells as text. */
data class Row(
    val section: String,
    val cells: List<String>,
)

/**
 * The reconciliation work-paper as an immutable value with named sections. Build it with [WorkPaper.build]; the
 * sections other than the header, attestation and [reconciledTo] come from one [ReconciliationResult], so
 * [unexplainedResidual] is always exactly the lines with a non-zero residual and never merged into
 * [classifiedVariances].
 */
class WorkPaper private constructor(
    val header: Map<String, String>,
    val comparison: List<Comparison>,
    val classifiedVariances: List<ClassifiedVariance>,
    val unexplainedResidual: List<UnexplainedLine>,
    val attestation: List<AttestationEntry>,
    val reconciledTo: String,
) {
    /** True only with at least one attestation entry. */
    val attested: Boolean get() = attestation.isNotEmpty()

    /** The plain-value export in the fixed section order; each section starts with a row of column titles. */
    fun toRows(): List<Row> =
        buildList {
            add(Row(HEADER, listOf("name", "value")))
            header.toSortedMap().forEach { (k, v) -> add(Row(HEADER, listOf(k, v))) }
            add(Row(HEADER, listOf(RECONCILED_TO, reconciledTo)))
            add(Row(COMPARISON, listOf("key", "return", "submitted", "variance", "missingSide")))
            comparison.forEach {
                val cells = listOf(it.key, "${it.returnAmount}", "${it.submittedAmount}", "${it.variance}")
                add(Row(COMPARISON, cells + "${it.missingSide}"))
            }
            add(Row(CLASSIFIED, listOf("key", "category", "label", "amount", "linkedRecords")))
            classifiedVariances.forEach {
                val cells = listOf(it.key, it.category.code, it.category.label, "${it.amount}")
                add(Row(CLASSIFIED, cells + it.linkedRecords.joinToString(";")))
            }
            add(Row(UNEXPLAINED, listOf("key", "variance", "residual")))
            unexplainedResidual.forEach { add(Row(UNEXPLAINED, listOf(it.key, "${it.variance}", "${it.residual}"))) }
            add(Row(ATTESTATION, listOf("role", "person", "timestamp")))
            attestation.forEach { add(Row(ATTESTATION, listOf(it.role, it.person, "${it.timestamp}"))) }
        }

    companion object {
        const val RECONCILED_TO = "reconciledTo"
        const val HEADER = "header"
        const val COMPARISON = "comparison"
        const val CLASSIFIED = "classified variances"
        const val UNEXPLAINED = "unexplained residual"
        const val ATTESTATION = "attestation"

        /**
         * Throws [IllegalArgumentException] for a header lacking a required key, a blank [reconciledTo] or an
         * attestation entry with a blank role or person, and [ReservedHeaderKeyException] for a header key equal to
         * `reconciledTo`. An empty attestation list is allowed and reports `attested = false`.
         */
        fun build(
            spec: WorkPaperSpec,
            header: Map<String, String>,
            result: ReconciliationResult,
            attestation: List<AttestationEntry>,
            reconciledTo: String,
        ): WorkPaper {
            if (RECONCILED_TO in header.keys) throw ReservedHeaderKeyException(RECONCILED_TO)
            val missing = spec.requiredHeaderKeys - header.keys
            require(missing.isEmpty()) { "Header lacks required keys: ${missing.sorted()}" }
            require(reconciledTo.isNotBlank()) { "reconciledTo must not be blank" }
            attestation.forEach {
                require(it.role.isNotBlank()) { "An attestation role must not be blank" }
                require(it.person.isNotBlank()) { "An attestation person must not be blank" }
            }
            return WorkPaper(
                header.toMap(),
                result.comparison,
                result.classifiedVariances,
                result.unexplained,
                attestation.toList(),
                reconciledTo,
            )
        }
    }
}
