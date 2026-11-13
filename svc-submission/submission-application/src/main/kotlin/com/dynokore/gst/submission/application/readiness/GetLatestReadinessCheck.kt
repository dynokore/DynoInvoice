package com.dynokore.gst.submission.application.readiness

import com.dynokore.gst.submission.application.gate.OverrideAuthority
import com.dynokore.gst.submission.application.gate.VALIDATION_OVERRIDE

/** The answer of [GetLatestReadinessCheck]. */
sealed interface LatestCheckResult {
    data class Found(
        val check: StoredReadinessCheck,
    ) : LatestCheckResult

    data object NotFound : LatestCheckResult

    data object Forbidden : LatestCheckResult
}

/**
 * The read behind the contract operation `getLatestReadinessCheck` (INV-REQ-500, INV-Q-21110 A). The permission check
 * (`validation.override`, INV-Q-10027) runs before the reader, so a denied caller learns nothing about the period.
 */
class GetLatestReadinessCheck(
    private val reader: ReadinessCheckReader,
    private val authority: OverrideAuthority,
) {
    fun execute(
        userId: String,
        entityRef: String,
        periodRef: String,
    ): LatestCheckResult {
        if (!authority.holds(userId, VALIDATION_OVERRIDE, entityRef)) {
            return LatestCheckResult.Forbidden
        }
        val check = reader.latest(entityRef, periodRef)
        return if (check == null) LatestCheckResult.NotFound else LatestCheckResult.Found(check)
    }
}
