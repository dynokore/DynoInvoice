package com.dynokore.gst.payload.application.version

import com.dynokore.gst.payload.application.override.OverrideAuthority
import com.dynokore.gst.payload.application.override.VALIDATION_OVERRIDE
import java.util.UUID

/** The answer of [GetLatestDocumentVersion]; only [Found] carries a version. */
sealed interface LatestVersionResult {
    data class Found(
        val docUuid: UUID,
        val version: Int,
    ) : LatestVersionResult

    data object NotFound : LatestVersionResult

    data object Forbidden : LatestVersionResult
}

/** The read of the latest version of a document, for the caller that may override it (INV-REQ-500). */
class GetLatestDocumentVersion(
    private val versions: LatestDocumentVersions,
    private val authority: OverrideAuthority,
) {
    @Suppress("ReturnCount")
    fun execute(
        userId: String,
        docUuid: UUID,
    ): LatestVersionResult {
        val latest = versions.latest(docUuid) ?: return LatestVersionResult.NotFound
        if (!authority.holds(userId, VALIDATION_OVERRIDE, latest.entityRef)) return LatestVersionResult.Forbidden
        return LatestVersionResult.Found(docUuid, latest.version)
    }
}
