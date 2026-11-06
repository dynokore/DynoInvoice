package com.dynokore.gst.payload.application.version

import com.dynokore.gst.payload.domain.version.VersionFacts
import java.util.UUID

/** Stores the facts of a document version; the tenant is the one bound to the request (INV-REQ-1293). */
interface VersionFactsRecorder {
    /** True when it stored the facts, false when the version already had a row; an existing row is never altered. */
    fun record(
        docUuid: UUID,
        version: Int,
        facts: VersionFacts,
    ): Boolean
}

/** Reads the facts of a document version; the tenant is the one bound to the request (INV-REQ-1293). */
interface VersionFactsReader {
    /** Null for an unknown version, another tenant's version and a stored row whose total is absent. */
    fun find(
        docUuid: UUID,
        version: Int,
    ): VersionFacts?
}
