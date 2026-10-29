package com.dynokore.gst.evidence.domain.retention

import java.time.Duration
import java.time.Instant

/** The custody holder notice window in whole days (VP-66); the value arrives from the resolved setting. */
data class NoticeWindow(
    val days: Int,
) {
    init {
        require(days > 0) { "The notice window must be longer than 0 days, was $days" }
    }
}

/** Whether deletion at retention end may proceed to co-approval. */
sealed interface DeletionGate {
    /** The notice window is still open; it closes at [closesAt]. */
    data class WindowOpen(
        val closesAt: Instant,
    ) : DeletionGate

    /** A legal hold is in force. */
    data object HoldInForce : DeletionGate

    /** The window has closed and no hold is in force. */
    data object MayProceedToCoApproval : DeletionGate
}

/** Decides the deletion gate from facts and a clock argument; a tie or an inconsistent clock refuses (fail-safe). */
object NoticeWindowGate {
    fun decide(
        notifiedAt: Instant,
        window: NoticeWindow,
        holdInForce: Boolean,
        now: Instant,
    ): DeletionGate {
        if (holdInForce) return DeletionGate.HoldInForce
        val closesAt = notifiedAt.plus(Duration.ofDays(window.days.toLong()))
        return if (now.isAfter(closesAt)) DeletionGate.MayProceedToCoApproval else DeletionGate.WindowOpen(closesAt)
    }
}
