package com.dynokore.gst.tenancy.application.rendition

import com.dynokore.gst.tenancy.domain.AttestationState
import com.dynokore.gst.tenancy.domain.Renderer
import java.time.Instant

/** Why a history row was written. */
enum class RenditionRecordKind {
    /** An attestation was recorded (the first or a replacement). */
    ATTESTED,

    /** The state of the current attestation changed (for example to REQUESTED_AGAIN). */
    STATE_CHANGED,
}

/** One row of the append-only attestation history (INV-REQ-1036 behavior 1). [attestedBy] is a user ID. */
data class RenditionAttestationRecord(
    val seq: Long,
    val kind: RenditionRecordKind,
    val renderer: Renderer,
    val attestedBy: String,
    val attestedAt: Instant,
    val state: AttestationState,
)

/** The append-only attestation history of one entity and document class of the bound tenant (adapter: JooqRenditionStore). */
interface RenditionAttestationHistory {
    /** Every record of the entity and class, ordered by `seq`; empty when none. */
    fun list(
        entityRef: String,
        documentClass: String,
    ): List<RenditionAttestationRecord>
}
