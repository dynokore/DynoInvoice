package com.dynokore.gst.canonical.application.document

import java.util.UUID

/**
 * The hold codes that masterdata-svc resolution (MasterDataResolved) can release. The catalogue row `MDM-001`
 * (platform-contracts `catalogue/errors/error-codes.yaml`: HOLD, "AUTO_RELEASE on counterparty resolution") is the only
 * HOLD code whose disposition names counterparty resolution (coordinator ruling round zj). Compared by equality.
 */
object MasterDataHoldCodes {
    val CODES: Set<String> = setOf("MDM-001")
}

/** The members of one DocumentReleased event; the adapter slice builds the payload and the `docsequence`. */
data class ReleasedEvent(
    val docUuid: UUID,
    val releasedHoldReasons: List<String>,
    val entityRef: String,
)

/** Port of the release columns of the `canonical.document` table. Called inside the caller's transaction. */
interface DocumentReleaseStore {
    /** Reads the document under a row lock; null when the bound tenant sees no such document. */
    fun lockForRelease(docUuid: UUID): HoldableDocument?

    /** Sets the stored state to INGESTED and clears the stored hold reasons. */
    fun markReleased(docUuid: UUID)
}

/** Port that stages the DocumentReleased event in the caller's transaction (transactional outbox). */
fun interface ReleasedEventPublisher {
    fun stage(event: ReleasedEvent)
}
