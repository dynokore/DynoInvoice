package com.dynokore.gst.submission.application.readiness

/** The check ids of the RELEASED overrides of the entity and period of the bound tenant (INV-REQ-72). */
fun interface ReleasedOverrideChecks {
    fun releasedCheckIds(
        entityRef: String,
        periodRef: String,
    ): Set<String>
}

/** The id of the readiness check that held the document in the entity and period, or null when none did. */
fun interface HoldingChecks {
    fun holdingCheckId(
        entityRef: String,
        periodRef: String,
        docUuid: String,
    ): String?
}
