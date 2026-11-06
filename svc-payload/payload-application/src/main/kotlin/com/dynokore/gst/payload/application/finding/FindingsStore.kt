package com.dynokore.gst.payload.application.finding

import com.dynokore.gst.payload.domain.finding.StoredFinding
import java.util.UUID

/** Writes the BLOCK findings of a document version; the tenant is the one bound to the request (INV-REQ-1180). */
interface FindingsRecorder {
    /** Replaces all findings of ([docUuid], [version]) with [findings] in one call. */
    fun replace(
        docUuid: UUID,
        version: Int,
        findings: List<StoredFinding>,
    )
}

/** Reads the BLOCK findings of a document version; the tenant is the one bound to the request (INV-REQ-1293). */
interface FindingsReader {
    fun blocking(
        docUuid: UUID,
        version: Int,
    ): List<StoredFinding>
}
