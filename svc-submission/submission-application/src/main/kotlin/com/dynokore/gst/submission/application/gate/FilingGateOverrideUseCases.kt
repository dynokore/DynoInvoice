package com.dynokore.gst.submission.application.gate

import com.dynokore.gst.submission.domain.gate.Allowed
import com.dynokore.gst.submission.domain.gate.FilingGate
import com.dynokore.gst.submission.domain.gate.NoBlock
import com.dynokore.gst.submission.domain.gate.OverrideStatus
import com.dynokore.gst.submission.domain.gate.REFUSAL_CODE
import com.dynokore.gst.submission.domain.gate.ReleaseOutcome
import com.dynokore.gst.submission.domain.gate.Stale

/** The stored override with its status at read time (SUPERSEDED is derived). */
data class OverrideEntry(
    val override: StoredOverride,
    val status: OverrideStatus,
)

/** The answer of the use cases; the HTTP status is mapped by the adapter slice. */
sealed interface OverrideResult {
    /** 201. */
    data class Released(
        val entry: OverrideEntry,
    ) : OverrideResult

    /** 202. */
    data class AwaitsSecond(
        val entry: OverrideEntry,
    ) : OverrideResult

    /** 200 of the read. */
    data class Listed(
        val entries: List<OverrideEntry>,
    ) : OverrideResult

    /** 400 SRT-001. */
    data object BadRequest : OverrideResult

    /** 403 SRT-004. */
    data object Forbidden : OverrideResult

    /** 404 SRT-005. */
    data object NotFound : OverrideResult

    /** 409 SRT-008. */
    data object Conflict : OverrideResult
}

const val BAD_REQUEST_CODE = "SRT-001"
const val FORBIDDEN_CODE = "SRT-004"

private fun statusOf(
    row: StoredOverride,
    latestCheckId: String?,
): OverrideStatus =
    if (row.status == OverrideStatus.PENDING && row.checkId != latestCheckId) OverrideStatus.SUPERSEDED else row.status

/** The first approval: stores a pending override or releases it at once (INV-Q-10691 R2). */
class RequestOverride(
    private val authority: OverrideAuthority,
    private val modes: GateModes,
    private val checks: LatestChecks,
    private val decisions: ReleaseDecisions,
    private val store: OverrideStore,
    private val audit: AuditTrail,
) {
    fun execute(
        userId: String,
        entityRef: String,
        periodRef: String,
        checkId: String,
        reason: String?,
    ): OverrideResult =
        refusal(userId, entityRef, periodRef, checkId, reason)
            ?: when (decisions.decide(entityRef, REFUSAL_CODE, userId, emptyList())) {
                is ReleaseOutcome.Denied -> {
                    OverrideResult.Forbidden
                }

                ReleaseOutcome.AwaitsSecond -> {
                    pending(
                        NewOverride(
                            entityRef,
                            periodRef,
                            checkId,
                            reason.orEmpty(),
                            OverrideStatus.PENDING,
                            userId,
                            null,
                        ),
                    )
                }

                ReleaseOutcome.Released -> {
                    releasedAtOnce(
                        NewOverride(
                            entityRef,
                            periodRef,
                            checkId,
                            reason.orEmpty(),
                            OverrideStatus.RELEASED,
                            userId,
                            userId,
                        ),
                    )
                }
            }

    private fun refusal(
        userId: String,
        entityRef: String,
        periodRef: String,
        checkId: String,
        reason: String?,
    ): OverrideResult? =
        when {
            !authority.holds(userId, VALIDATION_OVERRIDE, entityRef) -> {
                OverrideResult.Forbidden
            }

            reason.isNullOrBlank() || checkId.isEmpty() -> {
                OverrideResult.BadRequest
            }

            else -> {
                when (
                    FilingGate.canOverride(
                        modes.modeOf(entityRef),
                        checks.latest(entityRef, periodRef),
                        checks.knownCheckIds(entityRef, periodRef),
                        checkId,
                    )
                ) {
                    Allowed -> null
                    is NoBlock -> OverrideResult.NotFound
                    is Stale -> OverrideResult.Conflict
                }
            }
        }

    private fun pending(row: NewOverride): OverrideResult {
        val stored = store.insert(row)
        audit.requested(row.requestedBy, stored)
        return OverrideResult.AwaitsSecond(OverrideEntry(stored, stored.status))
    }

    private fun releasedAtOnce(row: NewOverride): OverrideResult {
        val stored = store.insert(row)
        audit.requested(row.requestedBy, stored)
        audit.released(row.requestedBy, stored)
        return OverrideResult.Released(OverrideEntry(stored, stored.status))
    }
}

/** The second approval: releases a pending override by one conditional update (INV-Q-10691 R3). */
class ApproveOverride(
    private val authority: OverrideAuthority,
    private val checks: LatestChecks,
    private val decisions: ReleaseDecisions,
    private val store: OverrideStore,
    private val audit: AuditTrail,
) {
    fun execute(
        userId: String,
        entityRef: String,
        periodRef: String,
        overrideId: String,
    ): OverrideResult =
        if (!authority.holds(userId, VALIDATION_OVERRIDE, entityRef)) {
            OverrideResult.Forbidden
        } else {
            store.findPending(entityRef, periodRef, overrideId)?.let { complete(userId, it) } ?: OverrideResult.NotFound
        }

    private fun complete(
        userId: String,
        row: StoredOverride,
    ): OverrideResult =
        if (checks.latest(row.entityRef, row.periodRef)?.checkId != row.checkId) {
            OverrideResult.Conflict
        } else {
            when (decisions.decide(row.entityRef, REFUSAL_CODE, userId, listOf(row.requestedBy))) {
                is ReleaseOutcome.Denied -> OverrideResult.Forbidden
                ReleaseOutcome.AwaitsSecond -> awaiting(userId, row)
                ReleaseOutcome.Released -> release(userId, row)
            }
        }

    private fun awaiting(
        userId: String,
        row: StoredOverride,
    ): OverrideResult {
        audit.approved(userId, row)
        return OverrideResult.AwaitsSecond(OverrideEntry(row, row.status))
    }

    private fun release(
        userId: String,
        row: StoredOverride,
    ): OverrideResult {
        if (!store.release(row.id, userId)) return OverrideResult.NotFound
        val released = row.copy(status = OverrideStatus.RELEASED, releasedBy = userId)
        audit.released(userId, released)
        return OverrideResult.Released(OverrideEntry(released, OverrideStatus.RELEASED))
    }
}

/** The read of the overrides of one period (INV-Q-10691 R4). */
class ReadOverrides(
    private val authority: OverrideAuthority,
    private val checks: LatestChecks,
    private val store: OverrideStore,
) {
    fun execute(
        userId: String,
        entityRef: String,
        periodRef: String,
    ): OverrideResult {
        if (!authority.holds(userId, VALIDATION_OVERRIDE, entityRef)) return OverrideResult.Forbidden
        val latestId = checks.latest(entityRef, periodRef)?.checkId
        return OverrideResult.Listed(
            store.entries(entityRef, periodRef).map { OverrideEntry(it, statusOf(it, latestId)) },
        )
    }
}
