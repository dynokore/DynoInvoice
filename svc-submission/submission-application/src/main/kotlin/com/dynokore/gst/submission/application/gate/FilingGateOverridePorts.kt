package com.dynokore.gst.submission.application.gate

import com.dynokore.gst.submission.domain.gate.GateMode
import com.dynokore.gst.submission.domain.gate.LatestCheck
import com.dynokore.gst.submission.domain.gate.OverrideStatus
import com.dynokore.gst.submission.domain.gate.ReleaseOutcome

/** The permission key of the request, the approval and the read (catalogue key `validation.override`, INV-REQ-72). */
const val VALIDATION_OVERRIDE = "validation.override"

/** Decides one scoped permission per request (`AuthorityReplica.decide` in the adapter slice, INV-Q-10027). */
fun interface OverrideAuthority {
    fun holds(
        userId: String,
        permission: String,
        entityRef: String,
    ): Boolean
}

/**
 * Asks `decideOverrideRelease` with `errorCode` = `REFUSAL_CODE` and no document reference, version or total. An error,
 * a timeout or an unreadable answer is [ReleaseOutcome.Denied].
 */
fun interface ReleaseDecisions {
    fun decide(
        entityRef: String,
        errorCode: String,
        approverRef: String,
        priorApproverRefs: List<String>,
    ): ReleaseOutcome
}

/** The readiness checks of a period. */
interface LatestChecks {
    fun latest(
        entityRef: String,
        periodRef: String,
    ): LatestCheck?

    fun knownCheckIds(
        entityRef: String,
        periodRef: String,
    ): Set<String>
}

/** The gate mode (VP-14) of an entity. */
fun interface GateModes {
    fun modeOf(entityRef: String): GateMode
}

/** An override before it is stored; [status] is stored, so PENDING or RELEASED. */
data class NewOverride(
    val entityRef: String,
    val periodRef: String,
    val checkId: String,
    val reason: String,
    val status: OverrideStatus,
    val requestedBy: String,
    val releasedBy: String?,
) {
    init {
        require(status != OverrideStatus.SUPERSEDED) { "SUPERSEDED is derived and never stored" }
        require(
            (status == OverrideStatus.RELEASED) == (releasedBy != null),
        ) { "releasedBy is set exactly when RELEASED" }
    }
}

/** A stored override. */
data class StoredOverride(
    val id: String,
    val entityRef: String,
    val periodRef: String,
    val checkId: String,
    val reason: String,
    val status: OverrideStatus,
    val requestedBy: String,
    val releasedBy: String?,
)

/** The store of the overrides of the caller's tenant; a row of another tenant is never returned. */
interface OverrideStore {
    /** Stores the row and returns it with its id. */
    fun insert(override: NewOverride): StoredOverride

    /** The PENDING row [overrideId] of the entity and period, or nothing (unknown, released or of another tenant). */
    fun findPending(
        entityRef: String,
        periodRef: String,
        overrideId: String,
    ): StoredOverride?

    /** One conditional update from PENDING to RELEASED; true when it changed one row. */
    fun release(
        overrideId: String,
        releasedBy: String,
    ): Boolean

    /** The entries of one period, with their stored status. */
    fun entries(
        entityRef: String,
        periodRef: String,
    ): List<StoredOverride>
}

/** The audit trail; entries name the acting user ID, never a role (INV-Q-10647). */
interface AuditTrail {
    fun requested(
        userId: String,
        override: StoredOverride,
    )

    fun released(
        userId: String,
        override: StoredOverride,
    )
}
