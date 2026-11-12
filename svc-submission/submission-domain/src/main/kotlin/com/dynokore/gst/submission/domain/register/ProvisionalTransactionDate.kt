package com.dynokore.gst.submission.domain.register

import java.time.LocalDate

/** Direction of a held document (`SALE` or `PURCHASE`, canonical openapi). */
enum class DocumentDirection { SALE, PURCHASE }

/** The entity's election for the date of a purchase with a supplier invoice (INV-REQ-67, INV-REQ-1283). */
sealed interface DateElection {
    data object Issuance : DateElection

    data object Posting : DateElection

    data object Unknown : DateElection
}

/**
 * The provisional transaction date of a document held before its scope verdict: the case table of INV-NOTE-2 section
 * 10.1 as INV-REQ-570 behavior 2 words it. Where the case or the election is not known, or the table names the
 * posting date and none is present, the earliest known candidate date is used; no date is ever guessed.
 */
object ProvisionalTransactionDate {
    fun select(
        direction: DocumentDirection,
        invoiceIssuedOrReceived: Boolean?,
        election: DateElection,
        issuanceDate: LocalDate,
        postingDate: LocalDate?,
    ): LocalDate {
        val earliest = listOfNotNull(issuanceDate, postingDate).min()
        return when (invoiceIssuedOrReceived) {
            null -> {
                earliest
            }

            false -> {
                postingDate ?: earliest
            }

            true -> {
                issuedCase(direction, election, issuanceDate, postingDate, earliest)
            }
        }
    }

    private fun issuedCase(
        direction: DocumentDirection,
        election: DateElection,
        issuanceDate: LocalDate,
        postingDate: LocalDate?,
        earliest: LocalDate,
    ): LocalDate =
        when (direction) {
            DocumentDirection.SALE -> {
                issuanceDate
            }

            DocumentDirection.PURCHASE -> {
                when (election) {
                    DateElection.Issuance -> issuanceDate
                    DateElection.Posting -> postingDate ?: issuanceDate
                    DateElection.Unknown -> earliest
                }
            }
        }
}
