package com.dynokore.gst.payload.application.override

import com.dynokore.gst.payload.domain.override.OverrideStatus
import com.dynokore.gst.payload.domain.override.ReleaseOutcome
import java.time.Instant
import java.util.UUID

/** The answer of the three use cases; the HTTP status is mapped by the adapter slice. */
sealed interface OverrideResult {
    /** 201. */
    data class Released(
        val entry: StoredOverride,
    ) : OverrideResult

    /** 202. */
    data class AwaitsSecond(
        val entry: StoredOverride,
    ) : OverrideResult

    /** 200 of the read. */
    data class Listed(
        val entries: List<StoredOverride>,
    ) : OverrideResult

    /** 400 SRT-001. */
    data object BadRequest : OverrideResult

    /** 403 SRT-004. */
    data object Forbidden : OverrideResult

    /** 404 SRT-005. */
    data object NotFound : OverrideResult

    /** 422 PAY-017: the code is not an OVERRIDABLE code that the version carries. */
    data object NotOverridable : OverrideResult

    /** 403 SRT-004: the decision of `decideOverrideRelease` is not a release (INV-Q-17227); a refused call writes none. */
    data class Refused(
        val reasonCode: String,
    ) : OverrideResult
}

/** The first approval: stores a pending override or releases it at once (INV-Q-10691 R2). */
class RequestOverride(
    private val versions: DocumentVersions,
    private val authority: OverrideAuthority,
    private val findings: BlockingFindings,
    private val decisions: ReleaseDecisions,
    private val store: OverrideStore,
    private val audit: AuditTrail,
) {
    @Suppress("ReturnCount")
    fun execute(
        userId: String,
        docUuid: UUID,
        version: Int,
        code: String,
        reason: String?,
    ): OverrideResult {
        // An unknown version, or one of another tenant, is NotFound on all three operations (INV-Q-17225).
        val facts = versions.find(docUuid, version) ?: return OverrideResult.NotFound
        if (!authority.holds(userId, VALIDATION_OVERRIDE, facts.entityRef)) return OverrideResult.Forbidden
        if (reason.isNullOrBlank()) return OverrideResult.BadRequest
        if (findings.disposition(docUuid, version, code) != FindingDisposition.OVERRIDABLE) {
            return OverrideResult.NotOverridable
        }
        store.find(docUuid, version, code)?.let { return recorded(it) }
        val outcome =
            decisions.decide(
                ReleaseRequest(facts.entityRef, docUuid, version, code, facts.totalSgd, userId, emptyList()),
            )
        return when (outcome) {
            is ReleaseOutcome.Denied -> {
                // Exact equality: look-alikes of NOT_OVERRIDABLE are Refused with their own code.
                if (outcome.reasonCode == ReleaseOutcome.NOT_OVERRIDABLE) {
                    OverrideResult.NotOverridable
                } else {
                    OverrideResult.Refused(outcome.reasonCode)
                }
            }

            ReleaseOutcome.AwaitsSecond -> {
                store(
                    NewOverride(docUuid, version, code, reason, OverrideStatus.PENDING, userId, null),
                )
            }

            ReleaseOutcome.Released -> {
                store(
                    NewOverride(docUuid, version, code, reason, OverrideStatus.RELEASED, userId, userId),
                )
            }
        }
    }

    /** A repeat reports the recorded override unchanged (contract repeatKey). */
    private fun recorded(row: StoredOverride): OverrideResult =
        if (row.status == OverrideStatus.RELEASED) OverrideResult.Released(row) else OverrideResult.AwaitsSecond(row)

    private fun store(row: NewOverride): OverrideResult =
        store.insert(row)?.let { written(row.requestedBy, it) } ?: raced(row)

    /** The key was taken between the read and the insert: report the recorded row, write no audit entry. */
    private fun raced(row: NewOverride): OverrideResult =
        store.find(row.docUuid, row.version, row.code)?.let(::recorded) ?: OverrideResult.NotFound

    private fun written(
        userId: String,
        stored: StoredOverride,
    ): OverrideResult {
        audit.requested(userId, stored)
        if (stored.status != OverrideStatus.RELEASED) return OverrideResult.AwaitsSecond(stored)
        audit.released(userId, stored)
        return OverrideResult.Released(stored)
    }
}

/** The second approval: releases a pending override by one conditional update (INV-Q-10691 R3). */
class ApproveOverride(
    private val versions: DocumentVersions,
    private val authority: OverrideAuthority,
    private val decisions: ReleaseDecisions,
    private val store: OverrideStore,
    private val audit: AuditTrail,
) {
    @Suppress("ReturnCount")
    fun execute(
        userId: String,
        docUuid: UUID,
        version: Int,
        code: String,
    ): OverrideResult {
        val facts = versions.find(docUuid, version) ?: return OverrideResult.NotFound
        if (!authority.holds(userId, VALIDATION_OVERRIDE, facts.entityRef)) return OverrideResult.Forbidden
        val pending =
            store.find(docUuid, version, code)?.takeIf { it.status == OverrideStatus.PENDING }
                ?: return OverrideResult.NotFound
        val outcome =
            decisions.decide(
                ReleaseRequest(
                    facts.entityRef,
                    docUuid,
                    version,
                    code,
                    facts.totalSgd,
                    userId,
                    listOf(pending.requestedBy),
                ),
            )
        // Only a release releases (INV-Q-17227); AwaitsSecond cannot answer with a prior approver (INV-Q-17229).
        when (outcome) {
            is ReleaseOutcome.Denied -> return OverrideResult.Refused(outcome.reasonCode)
            ReleaseOutcome.AwaitsSecond -> return OverrideResult.Refused(ReleaseOutcome.NOT_OVERRIDABLE)
            ReleaseOutcome.Released -> Unit
        }
        val released = store.release(docUuid, version, code, userId) ?: return OverrideResult.NotFound
        audit.released(userId, released)
        return OverrideResult.Released(released)
    }
}

/** The read of the overrides of one document version (INV-Q-10691 R4). */
class ReadOverrides(
    private val versions: DocumentVersions,
    private val authority: OverrideAuthority,
    private val store: OverrideStore,
) {
    @Suppress("ReturnCount")
    fun execute(
        userId: String,
        docUuid: UUID,
        version: Int,
        after: Pair<Instant, String>?,
        limit: Int,
    ): OverrideResult {
        val facts = versions.find(docUuid, version) ?: return OverrideResult.NotFound
        if (!authority.holds(userId, VALIDATION_OVERRIDE, facts.entityRef)) return OverrideResult.Forbidden
        return OverrideResult.Listed(store.entries(docUuid, version, after, limit))
    }
}
