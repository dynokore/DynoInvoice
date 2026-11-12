package com.dynokore.gst.notification.domain.alerting

import java.time.Instant

/** Trigger of the backlog alert (Alert.trigger, INV-REQ-1370). */
const val BACKLOG_ALERT_TRIGGER = "BACKLOG_THRESHOLD"

/** The four backlog values and the alert class each one is compared under. */
enum class BacklogValue(
    val alertClass: AlertClass,
) {
    QUEUE_DEPTH(AlertClass.BACKLOG),
    OLDEST_UNSENT_AGE(AlertClass.BACKLOG),
    FAILURE_RATE(AlertClass.BACKLOG),
    UNACKNOWLEDGED_COUNT(AlertClass.MISSING_ACKNOWLEDGEMENT),
}

/** One measurement of an entity and period; [oldestUnsentAgeSeconds] is null when no version is unsent. */
data class BacklogMeasurement(
    val entityRef: String,
    val periodRef: String,
    val measuredAt: Instant,
    val queueDepth: Long,
    val oldestUnsentAgeSeconds: Long?,
    val failureRate: Double,
    val unacknowledgedCount: Long,
)

/** The thresholds of the entity's alert policy; a null member is not configured (never a default). */
data class BacklogThresholds(
    val maxQueueDepth: Long? = null,
    val maxOldestUnsentAgeSeconds: Long? = null,
    val maxFailureRatio: Double? = null,
    val maxUnacknowledgedCount: Long? = null,
)

enum class BacklogDecisionKind { BREACHED, WITHIN_THRESHOLD, NOT_CONFIGURED }

data class BacklogClassDecision(
    val alertClass: AlertClass,
    val kind: BacklogDecisionKind,
    val breached: List<BacklogValue>,
    val notConfigured: List<BacklogValue>,
)

/** Pure comparison of the backlog values with the thresholds (INV-REQ-1370, INV-REQ-1113). */
object BacklogEvaluation {
    fun decide(
        measurement: BacklogMeasurement,
        alertClass: AlertClass,
        thresholds: BacklogThresholds?,
    ): BacklogClassDecision {
        val values = BacklogValue.entries.filter { it.alertClass == alertClass }
        val breached = mutableListOf<BacklogValue>()
        val notConfigured = mutableListOf<BacklogValue>()
        for (value in values) {
            val outcome = compare(value, measurement, thresholds)
            when (outcome) {
                null -> notConfigured += value
                true -> breached += value
                false -> Unit
            }
        }
        val kind =
            when {
                breached.isNotEmpty() -> BacklogDecisionKind.BREACHED
                values.isNotEmpty() && notConfigured.size == values.size -> BacklogDecisionKind.NOT_CONFIGURED
                else -> BacklogDecisionKind.WITHIN_THRESHOLD
            }
        return BacklogClassDecision(alertClass, kind, breached, notConfigured)
    }

    /** Null when the threshold is absent; else whether the value is strictly above its maximum. */
    private fun compare(
        value: BacklogValue,
        m: BacklogMeasurement,
        t: BacklogThresholds?,
    ): Boolean? =
        when (value) {
            BacklogValue.QUEUE_DEPTH -> {
                t?.maxQueueDepth?.let { m.queueDepth > it }
            }

            BacklogValue.OLDEST_UNSENT_AGE -> {
                t?.maxOldestUnsentAgeSeconds?.let { max -> m.oldestUnsentAgeSeconds?.let { it > max } ?: false }
            }

            BacklogValue.FAILURE_RATE -> {
                t?.maxFailureRatio?.let { m.failureRate > it }
            }

            BacklogValue.UNACKNOWLEDGED_COUNT -> {
                t?.maxUnacknowledgedCount?.let { m.unacknowledgedCount > it }
            }
        }
}
