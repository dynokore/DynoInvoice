package com.dynokore.gst.submission.application.readiness

import com.dynokore.gst.submission.domain.gate.CheckVerdict
import java.time.Instant

/** Largest page the reader serves (contract `listReadinessChecks`, maximum 500). */
const val MAX_READINESS_PAGE = 500

/**
 * One stored readiness check (INV-REQ-951). [seq] is the identity of the record; [checkId] is derived from it, so it is
 * unique without a counter lock. [approver] is the verified caller identity, never a role (INV-Q-10647).
 */
data class StoredReadinessCheck(
    val seq: Long,
    val checkId: String,
    val entityRef: String,
    val periodRef: String,
    val verdict: CheckVerdict,
    val checkedAt: Instant,
    val approver: String,
    val outcomeValues: String?,
) {
    init {
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(periodRef.isNotBlank()) { "periodRef must not be blank" }
        require(approver.isNotBlank()) { "approver must not be blank" }
    }

    companion object {
        fun checkIdOf(seq: Long): String = "chk-$seq"
    }
}

/** Appends a check in the caller's open tenant transaction; the tenant is the one bound to the request. */
interface ReadinessCheckRecorder {
    @Suppress("LongParameterList")
    fun append(
        entityRef: String,
        periodRef: String,
        verdict: CheckVerdict,
        checkedAt: Instant,
        approver: String,
        outcomeValues: String?,
    ): StoredReadinessCheck
}

/** Reads the checks of the bound tenant. A record is never changed or deleted. */
interface ReadinessCheckReader {
    fun latest(
        entityRef: String,
        periodRef: String,
    ): StoredReadinessCheck?

    /** Newest first; [before] is the `seq` of the last record of the previous page. */
    fun page(
        entityRef: String,
        periodRef: String,
        limit: Int,
        before: Long? = null,
    ): List<StoredReadinessCheck>
}
