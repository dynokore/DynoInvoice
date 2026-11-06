package com.dynokore.gst.payload.application.finding

import com.dynokore.gst.payload.domain.finding.StoredFinding
import com.dynokore.gst.payload.domain.finding.ValidationReason
import java.util.UUID

/** Reduces the reasons of one validation to its BLOCK findings and replaces those of the version (INV-REQ-1180). */
class RecordValidationFindings(
    private val recorder: FindingsRecorder,
) {
    /** Throws [IllegalArgumentException], with nothing written, for a [version] below 1 or an invalid severity. */
    fun record(
        docUuid: UUID,
        version: Int,
        reasons: List<ValidationReason>,
    ) {
        require(version >= 1) { "version must be at least 1, got $version" }
        recorder.replace(docUuid, version, StoredFinding.fromReasons(reasons))
    }
}
