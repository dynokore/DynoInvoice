package com.dynokore.gst.canonical.domain.document

import java.math.BigDecimal
import java.util.UUID

/** One row of `document_line`: a scalar item of `lines.value` of the canonical document (INV-NOTE-3 section 10.6). */
data class DerivedLine(
    val lineNumber: Int,
    val description: String,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val lineAmount: BigDecimal,
    val taxCode: String,
    val taxAmount: BigDecimal,
)

/** One row of `document_subtotal`: an item of `taxSubtotals.value`. */
data class DerivedSubtotal(
    val taxCode: String,
    val taxableAmount: BigDecimal,
    val taxAmount: BigDecimal,
)

/** The two roles of `document_party.role`. */
enum class PartyRole(
    val column: String,
) {
    SUPPLIER("supplier"),
    BUYER("buyer"),
}

/** One row of `document_party`: the party object as JSON text; the contract defines no scalar field of a party. */
data class DerivedParty(
    val role: PartyRole,
    val party: String,
)

/** Everything derived from the canonical document of one version. */
data class DerivedRows(
    val lines: List<DerivedLine>,
    val subtotals: List<DerivedSubtotal>,
    val parties: List<DerivedParty>,
)

/** A stored canonical document whose shape differs from the canonical contract; nothing of the version is written. */
class CanonicalShapeException(
    message: String,
) : IllegalArgumentException(message)

/** The subtotal coverage check C-6 (INV-NOTE-3 section 10.3) failed; the message names C-6. */
class SubtotalCoverageViolation(
    message: String,
) : IllegalStateException(message)

/** A version (or the version key) that a rebuild names. */
data class VersionKey(
    val docUuid: UUID,
    val version: Int,
)

/** The versions a rebuild wrote and the versions it refused, with the reason (which names C-6 or the shape). */
data class RebuildReport(
    val rebuilt: List<VersionKey>,
    val rejected: Map<VersionKey, String>,
)

/** C-6: every tax code of the lines has a subtotal row and every subtotal row has a line with that tax code. */
object SubtotalCoverage {
    fun check(rows: DerivedRows) {
        val lineCodes = rows.lines.map { it.taxCode }.toSet()
        val subtotalCodes = rows.subtotals.map { it.taxCode }.toSet()
        val uncovered = (lineCodes - subtotalCodes).sorted()
        val orphans = (subtotalCodes - lineCodes).sorted()
        if (uncovered.isNotEmpty() || orphans.isNotEmpty()) {
            throw SubtotalCoverageViolation(
                "C-6: line tax codes without a subtotal $uncovered; subtotal tax codes without a line $orphans",
            )
        }
    }
}
