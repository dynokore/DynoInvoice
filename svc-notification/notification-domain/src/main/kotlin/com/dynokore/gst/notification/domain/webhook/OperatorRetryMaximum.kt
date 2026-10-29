package com.dynokore.gst.notification.domain.webhook

import com.dynokore.gst.notification.domain.alerting.POL_OUT_OF_BOUNDS

/** Inclusive bounds of the operator maximum webhook retry window (VP-80), in hours. */
data class MaximumBounds(
    val minHours: Long,
    val maxHours: Long,
) {
    init {
        require(minHours >= 1) { "minHours must be at least 1" }
        require(maxHours >= minHours) { "maxHours must be at least minHours" }
    }
}

data class OperatorMaximum(
    val hours: Long,
) {
    init {
        require(hours >= 1) { "hours must be at least 1" }
    }
}

sealed interface MaximumDecision {
    data class Accepted(
        val maximum: OperatorMaximum,
    ) : MaximumDecision

    data class Refused(
        val code: String,
        val keeps: OperatorMaximum,
    ) : MaximumDecision
}

/** Decides the operator maximum retry window over plain values; default and bounds are arguments. */
object OperatorRetryMaximum {
    fun effective(
        configured: OperatorMaximum?,
        default: OperatorMaximum,
        bounds: MaximumBounds,
    ): OperatorMaximum {
        val result = configured ?: default
        require(result.hours in bounds.minHours..bounds.maxHours) {
            "operator maximum ${result.hours} is outside the bounds ${bounds.minHours}..${bounds.maxHours}"
        }
        return result
    }

    fun set(
        requested: OperatorMaximum,
        current: OperatorMaximum,
        bounds: MaximumBounds,
    ): MaximumDecision =
        if (requested.hours < bounds.minHours || requested.hours > bounds.maxHours) {
            MaximumDecision.Refused(POL_OUT_OF_BOUNDS, current)
        } else {
            MaximumDecision.Accepted(requested)
        }
}
