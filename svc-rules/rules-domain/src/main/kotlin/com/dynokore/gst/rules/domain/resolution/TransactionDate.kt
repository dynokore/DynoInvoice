package com.dynokore.gst.rules.domain.resolution

import com.dynokore.platform.rulepackkernel.PackType
import java.time.LocalDate

/**
 * The date a pack lookup resolves against: the document's own transaction date, never the current date
 * (INV-REQ-770, INV-BR-215). A lookup takes this type and nothing else, so the clock cannot reach the resolution path.
 */
@JvmInline
value class TransactionDate(
    val value: LocalDate,
) {
    override fun toString(): String = value.toString()
}

/**
 * The dates one document's lookups use (INV-REQ-770 behaviour 3, INV-AC-1782): its own date, and for a credit note with
 * populated preceding fields the preceding document's date, which the rate and code catalogue packs resolve at.
 */
data class ResolutionDates(
    val own: TransactionDate,
    val preceding: TransactionDate? = null,
) {
    fun forPack(packType: PackType): TransactionDate =
        if (packType in PackTypes.RESOLVED_AT_PRECEDING_DATE) preceding ?: own else own
}
