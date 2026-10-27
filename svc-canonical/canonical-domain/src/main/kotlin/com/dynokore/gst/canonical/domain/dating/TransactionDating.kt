package com.dynokore.gst.canonical.domain.dating

import java.time.LocalDate
import java.time.ZoneId

/** The outcome of [TransactionDating.decide]. */
sealed interface DatingDecision {
    /** The transaction date, the basis it was taken on, and the zone assumed for a zoneless posting (else null). */
    data class Dated(
        val transactionDate: LocalDate,
        val basis: DateBasis,
        val assumedZone: ZoneId?,
    ) : DatingDecision

    /** A purchase with no election in force is held (PER-011); nothing is dated on a guessed basis (INV-REQ-67 b3). */
    data class Held(
        val code: HoldCode,
    ) : DatingDecision

    /** The posting date decides and the posting timestamp is absent. */
    data object PostingTimestampRequired : DatingDecision {
        @Suppress("ktlint:standard:property-naming") // the name the task fixes
        const val refusalCode: String = "SRT-001"
    }
}

/** Step 2 of INV-NOTE-3 §4.2.3: pure, no clock, no state (INV-REQ-76). */
object TransactionDating {
    fun decide(input: DatingInput): DatingDecision {
        val invoiceDated = DatingDecision.Dated(input.documentDate, DateBasis.INVOICE_DATE, null)
        val basis = if (input.invoiceBacked) basisOf(input) else DateBasis.POSTING_DATE
        return when (basis) {
            null -> DatingDecision.Held(HoldCode.PER_011)
            DateBasis.INVOICE_DATE -> invoiceDated
            DateBasis.POSTING_DATE -> postingDate(input)
        }
    }

    /** The basis of an invoice-backed document: a sale always the invoice date, a purchase the election (or none). */
    private fun basisOf(input: DatingInput): DateBasis? =
        if (input.direction == Direction.SALE) DateBasis.INVOICE_DATE else input.election

    private fun postingDate(input: DatingInput): DatingDecision =
        when (val posting = input.posting) {
            null -> {
                DatingDecision.PostingTimestampRequired
            }

            is PostingMoment.Zoned -> {
                DatingDecision.Dated(
                    posting.at.atZoneSameInstant(SINGAPORE).toLocalDate(),
                    DateBasis.POSTING_DATE,
                    null,
                )
            }

            is PostingMoment.Zoneless -> {
                DatingDecision.Dated(
                    posting.at
                        .atZone(input.sourceZone)
                        .withZoneSameInstant(SINGAPORE)
                        .toLocalDate(),
                    DateBasis.POSTING_DATE,
                    input.sourceZone,
                )
            }
        }
}
