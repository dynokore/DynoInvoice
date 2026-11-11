package com.dynokore.gst.canonical.application.document

import com.dynokore.gst.canonical.domain.document.Direction
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/** The stored `canonical.document.state` values the hold use case reads and writes. */
object DocumentStates {
    const val INGESTED = "INGESTED"
    const val HELD = "HELD"
}

/** The facts of a document read for a hold, with the stored state and the stored hold reasons (empty when none). */
data class HoldableDocument(
    val docUuid: UUID,
    val entityRef: String,
    val direction: Direction,
    val documentDate: LocalDate,
    val prescribedPeriodRef: String,
    val state: String,
    val holdReasons: List<String>,
    val postingTimestamp: OffsetDateTime? = null,
    val invoiceIssuedOrReceived: Boolean? = null,
)

/** The members of one DocumentHeld event; the adapter slice builds the payload and the `docsequence`. */
data class HeldEvent(
    val docUuid: UUID,
    val holdReasons: List<String>,
    val entityRef: String,
    val direction: Direction,
    val documentDate: LocalDate,
    val prescribedPeriodRef: String,
    val postingTimestamp: OffsetDateTime?,
    val invoiceIssuedOrReceived: Boolean?,
)

/** Port of the hold columns of the `canonical.document` table. Called inside the caller's transaction. */
interface DocumentHoldStore {
    /** Reads the document under a row lock; null when the bound tenant sees no such document. */
    fun lockForHold(docUuid: UUID): HoldableDocument?

    /** Sets the stored state to HELD and stores [reasons], replacing any stored list. */
    fun markHeld(
        docUuid: UUID,
        reasons: List<String>,
    )
}

/** Port that stages the DocumentHeld event in the caller's transaction (transactional outbox). */
interface HeldEventPublisher {
    fun stage(event: HeldEvent)
}
