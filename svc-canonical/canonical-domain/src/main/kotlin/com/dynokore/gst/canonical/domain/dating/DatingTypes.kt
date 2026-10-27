package com.dynokore.gst.canonical.domain.dating

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** The direction of a recorded document; the members equal `RecordDocumentRequest.direction` of the contract. */
enum class Direction { SALE, PURCHASE }

/** The purchase transaction date basis of VP-22 (INV-REQ-1283); the members equal its option keys. */
enum class DateBasis { INVOICE_DATE, POSTING_DATE }

/** The hold codes of the dating decisions; the strings equal the PER-009 and PER-011 codes of the error catalogue. */
enum class HoldCode(
    val code: String,
) {
    PER_009("PER-009"),
    PER_011("PER-011"),
}

/** A posting timestamp as the source gave it: with an offset, or without one (read in the source's zone). */
sealed interface PostingMoment {
    data class Zoned(
        val at: OffsetDateTime,
    ) : PostingMoment

    data class Zoneless(
        val at: LocalDateTime,
    ) : PostingMoment
}

/** The zone the transaction date and the prescribed period are read in (INV-NOTE-3 §4.2.3). */
val SINGAPORE: ZoneId = ZoneId.of("Asia/Singapore")

/** What step 2 of the derivation order needs; [posting] is consulted only when the posting date decides. */
data class DatingInput(
    val direction: Direction,
    val invoiceBacked: Boolean,
    val documentDate: LocalDate,
    val posting: PostingMoment?,
    val sourceZone: ZoneId,
    val election: DateBasis?,
)
