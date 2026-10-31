package com.dynokore.gst.tenancy.domain

import java.math.BigDecimal

/** One row of a production sample: who the counterparty is, and the amount (INV-REQ-1130). */
data class SampleRow(
    val counterpartyName: String,
    val counterpartyId: String,
    val amount: BigDecimal,
)

/**
 * Anonymisation of a production sample (INV-REQ-1130 behavior 3): counterparty identities are replaced and amounts are
 * kept. One counterparty keeps one stand-in across the sample, so the shape of the data survives; the stand-in is the
 * order of first appearance, so nothing of the original identity is carried over.
 */
object SampleAnonymiser {
    fun anonymise(rows: List<SampleRow>): List<SampleRow> {
        val standIns = LinkedHashMap<Pair<String, String>, Int>()
        return rows.map { row ->
            val n = standIns.getOrPut(row.counterpartyName to row.counterpartyId) { standIns.size + 1 }
            SampleRow("Counterparty $n", "ANON-$n", row.amount)
        }
    }
}
