package com.dynokore.gst.notification.domain.webhook

import com.dynokore.gst.notification.domain.alerting.POL_OUT_OF_BOUNDS
import java.time.Duration
import java.time.Instant

/** A webhook retry window in hours (VP-67); at least 1, the minimum of the catalogue row. */
data class RetryWindow(
    val hours: Long,
) {
    init {
        require(hours >= 1) { "Retry window must be at least 1 hour: $hours" }
    }
}

/** Outcome of setting a subscription's retry window. */
sealed interface RetryWindowDecision {
    data class Accepted(
        val window: RetryWindow,
    ) : RetryWindowDecision

    data class Refused(
        val code: String,
        val keeps: RetryWindow,
    ) : RetryWindowDecision
}

/** Whether a delivery is retried again or dead-lettered. */
sealed interface DeliveryRetry {
    data object Retry : DeliveryRetry

    data object DeadLetter : DeliveryRetry
}

/** Pure decisions on a webhook retry window against the operator maximum (INV-REQ-2463, VP-67). */
object WebhookRetryWindow {
    /** Refuses a window above [operatorMaximum]; equality is accepted. The subscription keeps [current] on refusal. */
    fun set(
        requested: RetryWindow,
        current: RetryWindow,
        operatorMaximum: RetryWindow,
    ): RetryWindowDecision =
        if (requested.hours > operatorMaximum.hours) {
            RetryWindowDecision.Refused(POL_OUT_OF_BOUNDS, current)
        } else {
            RetryWindowDecision.Accepted(requested)
        }

    /** The smaller of the stored window and the operator maximum; the stored value is not changed. */
    fun effective(
        stored: RetryWindow,
        operatorMaximum: RetryWindow,
    ): RetryWindow = if (stored.hours <= operatorMaximum.hours) stored else operatorMaximum

    /** Retry while [now] is before first attempt plus window; at or after that instant the delivery is dead-lettered. */
    fun next(
        firstAttemptAt: Instant,
        window: RetryWindow,
        now: Instant,
    ): DeliveryRetry =
        if (now.isBefore(firstAttemptAt.plus(Duration.ofHours(window.hours)))) {
            DeliveryRetry.Retry
        } else {
            DeliveryRetry.DeadLetter
        }
}
