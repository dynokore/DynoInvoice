package com.dynokore.gst.canonical.domain.source

import java.util.UUID

/** The four-part idempotency key of `source_event` (INV-REQ-1191 behavior 1). */
data class SourceKey(
    val tenantId: UUID,
    val sourceSystem: String,
    val sourceKey: String,
    val sourceRevision: Long,
) {
    init {
        require(sourceSystem.isNotBlank()) { "source_system is blank" }
        require(sourceKey.isNotBlank()) { "source_key is blank" }
        require(sourceRevision >= 0) { "source_revision is negative" }
    }
}

/** The outcome of recording an event: its Doc UUID and whether this call inserted the row. */
data class RecordedEvent(
    val docUuid: UUID,
    val inserted: Boolean,
)
