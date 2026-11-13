package com.dynokore.gst.consoleBff.application.override

import java.time.Instant

sealed interface OverrideOutcome {
    data class Overridden(
        val overriddenAt: Instant,
    ) : OverrideOutcome

    data class PendingApproval(
        val requestedAt: Instant,
    ) : OverrideOutcome

    data class Relayed(
        val refusal: OwnerRefusal,
    ) : OverrideOutcome

    data object NotFound : OverrideOutcome

    data object Forbidden : OverrideOutcome

    data class Unavailable(
        val reason: String,
    ) : OverrideOutcome
}

/** Routes an exception override to its owning service; stores nothing (single writer per state). */
class OverrideException(
    private val targets: OverrideTargets,
    private val authority: OverrideAuthority,
    private val payload: PayloadOverrides,
    private val submission: SubmissionOverrides,
) {
    fun execute(
        tenantId: String,
        userId: String,
        exceptionId: String,
        reason: String,
    ): OverrideOutcome {
        val row = targets.find(exceptionId) ?: return OverrideOutcome.NotFound
        if (!authority.holds(tenantId, userId, row.entityRef)) return OverrideOutcome.Forbidden
        if (row.state != OPEN) return OverrideOutcome.NotFound
        return when (RouteTable.routeOf(row.code)) {
            Route.PAYLOAD -> if (row.docUuid.isEmpty()) OverrideOutcome.NotFound else viaPayload(row, reason)
            Route.SUBMISSION -> if (row.periodRef.isEmpty()) OverrideOutcome.NotFound else viaSubmission(row, reason)
            null -> OverrideOutcome.NotFound
        }
    }

    private fun viaPayload(
        row: OverridableRow,
        reason: String,
    ): OverrideOutcome =
        when (val read = payload.latestVersion(row.docUuid)) {
            is OwnerReply.Ok -> mapped(payload.override(row.docUuid, read.value, row.code, reason))
            is OwnerReply.Refused -> OverrideOutcome.Relayed(read.refusal)
            is OwnerReply.Unavailable -> OverrideOutcome.Unavailable(read.reason)
        }

    private fun viaSubmission(
        row: OverridableRow,
        reason: String,
    ): OverrideOutcome =
        when (val read = submission.latestCheck(row.entityRef, row.periodRef)) {
            is OwnerReply.Ok -> mapped(submission.override(row.entityRef, row.periodRef, read.value, reason))
            is OwnerReply.Refused -> OverrideOutcome.Relayed(read.refusal)
            is OwnerReply.Unavailable -> OverrideOutcome.Unavailable(read.reason)
        }

    private fun mapped(reply: OwnerReply<Release>): OverrideOutcome =
        when (reply) {
            is OwnerReply.Ok -> {
                when (val release = reply.value) {
                    is Release.Released -> OverrideOutcome.Overridden(release.overriddenAt)
                    is Release.Pending -> OverrideOutcome.PendingApproval(release.requestedAt)
                }
            }

            is OwnerReply.Refused -> {
                OverrideOutcome.Relayed(reply.refusal)
            }

            is OwnerReply.Unavailable -> {
                OverrideOutcome.Unavailable(reply.reason)
            }
        }

    private companion object {
        const val OPEN = "OPEN"
    }
}
