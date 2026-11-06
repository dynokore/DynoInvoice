package com.dynokore.gst.payload.application.version

import com.dynokore.gst.payload.domain.version.VersionFacts
import java.util.UUID

/** Records the facts of a canonical version once; a version is immutable (INV-REQ-1293). */
class RecordVersionFacts(
    private val recorder: VersionFactsRecorder,
) {
    /** Throws [IllegalArgumentException], with nothing written, for a [version] below 1. */
    fun record(
        docUuid: UUID,
        version: Int,
        facts: VersionFacts,
    ): Boolean {
        require(version >= 1) { "version must be at least 1, got $version" }
        return recorder.record(docUuid, version, facts)
    }
}
