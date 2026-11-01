package com.dynokore.gst.canonical.domain.source

import com.dynokore.gst.canonical.domain.document.Direction
import java.util.UUID

/** What an amendment may not change: entity, direction and document type (INV-REQ-793 behavior 3). */
data class SourceIdentity(
    val entityId: UUID,
    val direction: Direction,
    val documentTypeCode: Short,
)

/** A recorded source key: its Doc UUID, every recorded revision and the identity it was recorded with. */
data class RecordedSource(
    val docUuid: UUID,
    val revisions: Set<Long>,
    val identity: SourceIdentity,
) {
    init {
        require(revisions.isNotEmpty()) { "revisions is empty" }
    }
}

enum class SourceRefusal { REVISION_BELOW_RECORDED, IDENTITY_CHANGED }

sealed interface SourceDecision {
    object New : SourceDecision

    data class Replay(
        val docUuid: UUID,
    ) : SourceDecision

    data class Amend(
        val docUuid: UUID,
    ) : SourceDecision

    data class Refused(
        val reason: SourceRefusal,
    ) : SourceDecision
}

/** Decides whether an arriving source event is new, a replay, an amendment or refused (INV-REQ-793). */
object SourceRevisionDecision {
    fun decide(
        recorded: RecordedSource?,
        revision: Long,
        identity: SourceIdentity,
    ): SourceDecision {
        require(revision >= 0) { "source_revision is negative" }
        return if (recorded == null) {
            SourceDecision.New
        } else if (revision in recorded.revisions) {
            SourceDecision.Replay(recorded.docUuid)
        } else if (revision < recorded.revisions.max()) {
            SourceDecision.Refused(SourceRefusal.REVISION_BELOW_RECORDED)
        } else if (identity != recorded.identity) {
            SourceDecision.Refused(SourceRefusal.IDENTITY_CHANGED)
        } else {
            SourceDecision.Amend(recorded.docUuid)
        }
    }
}
