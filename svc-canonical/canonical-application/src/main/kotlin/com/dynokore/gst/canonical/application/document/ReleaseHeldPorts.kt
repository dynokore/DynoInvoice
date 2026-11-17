package com.dynokore.gst.canonical.application.document

import java.time.Instant
import java.util.UUID

/**
 * Port that answers whether a user may release held documents of an entity. The adapter binds it to
 * `AuthorityReplica.decide` for the scoped key `held-document.release` (INV-Q-10027); this module names no role.
 */
fun interface ReleaseAuthority {
    fun permits(
        userId: String,
        entityRef: String,
    ): Boolean
}

/** The recorded release of one hold code of one document; [releasedBy] is the acting user ID (INV-Q-10647). */
data class RecordedRelease(
    val docUuid: UUID,
    val holdCode: String,
    val releasedBy: String,
    val releasedAt: Instant,
)

/** Port of the release records and the stored hold reasons. Called inside the caller's transaction. */
interface HeldReleaseRecords {
    /** The recorded release for (docUuid, holdCode), or null. */
    fun find(
        docUuid: UUID,
        holdCode: String,
    ): RecordedRelease?

    /** Stores the release; called at most once per (docUuid, holdCode). */
    fun record(release: RecordedRelease)

    /** The user ID stored with the hold when a person's act caused it, else null. */
    fun holdCause(
        docUuid: UUID,
        holdCode: String,
    ): String?

    /** Removes [holdCode] from the stored hold reasons of the document. */
    fun removeReason(
        docUuid: UUID,
        holdCode: String,
    )
}
