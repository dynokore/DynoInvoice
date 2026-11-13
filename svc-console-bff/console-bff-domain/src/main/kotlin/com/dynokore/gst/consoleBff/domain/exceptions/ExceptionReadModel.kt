package com.dynokore.gst.consoleBff.domain.exceptions

import com.dynokore.gst.consoleBff.domain.badge.ItemState
import java.time.Instant

/** Pure row builder of the exception read model (first slice: DocumentHeld and DocumentReleased). */
object ExceptionReadModel {
    /**
     * One OPEN row per distinct code of the hold reasons, in list order. The event-level owner and disposition belong
     * to the code named by the event member `code`, and only when that code is in the hold reasons; every other row
     * gets the empty string. No value is defaulted or normalised.
     */
    fun opened(
        tenant: String,
        held: DocumentHeldFacts,
        raisedAt: Instant,
    ): List<ExceptionRow> =
        held.holdReasons.distinct().map { reason ->
            val owns = held.code == reason
            ExceptionRow(
                tenantId = tenant,
                exceptionId = exceptionId(tenant, held.entityRef, held.docUuid, reason),
                entityRef = held.entityRef,
                docUuid = held.docUuid,
                code = reason,
                remediationOwner = if (owns) held.remediationOwner.orEmpty() else "",
                disposition = if (owns) held.disposition.orEmpty() else "",
                state = ItemState.OPEN,
                raisedAt = raisedAt,
                periodRef = held.prescribedPeriodRef,
                docsequence = held.docsequence,
            )
        }

    /**
     * The OPEN rows of the document whose code is released, as RESOLVED. No other row is returned; RESOLVED and
     * OVERRIDDEN rows are never returned (an override is a human decision).
     */
    fun release(
        rows: List<ExceptionRow>,
        released: DocumentReleasedFacts,
    ): List<ExceptionRow> =
        rows
            .filter { it.docUuid == released.docUuid && it.state == ItemState.OPEN }
            .filter { it.code in released.releasedHoldReasons }
            .map { it.copy(state = ItemState.RESOLVED) }
}
