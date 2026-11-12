package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.gst.notification.domain.alerting.BACKLOG_ALERT_TRIGGER
import com.dynokore.gst.notification.domain.alerting.BacklogDecisionKind
import com.dynokore.gst.notification.domain.alerting.BacklogEvaluation
import com.dynokore.gst.notification.domain.alerting.BacklogMeasurement
import com.dynokore.gst.notification.domain.alerting.BacklogThresholds
import com.dynokore.gst.notification.domain.alerting.BacklogValue
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime

/** The backlog thresholds of the entity's alert policy for one class; null when the entity has no policy row. */
interface BacklogThresholdSource {
    fun thresholds(
        tenant: TenantId,
        entityRef: String,
        alertClass: AlertClass,
    ): BacklogThresholds?
}

data class BacklogAlertDraft(
    val tenant: TenantId,
    val alertClass: AlertClass,
    val trigger: String,
    val entityRef: String,
    val periodRef: String,
    val raisedAt: Instant,
)

/** The alert store; idempotent per entity, class, trigger and period while the alert is open. */
interface BacklogAlertStore {
    /** True when newly raised, false when an alert for the same key is already open. */
    fun raise(draft: BacklogAlertDraft): Boolean
}

enum class BacklogResultKind { RAISED, ALREADY_OPEN, WITHIN_THRESHOLD, NOT_CONFIGURED }

data class BacklogClassResult(
    val alertClass: AlertClass,
    val kind: BacklogResultKind,
    val breached: List<BacklogValue>,
    val notConfigured: List<BacklogValue>,
)

data class HandleBacklogValuesResult(
    val results: List<BacklogClassResult>,
)

/** Handles BacklogValuesMeasured (INV-REQ-1370); duplicate delivery is absorbed by the consumer's platform inbox. */
class HandleBacklogValues(
    private val thresholds: BacklogThresholdSource,
    private val store: BacklogAlertStore,
    private val clock: Clock,
) {
    fun handle(
        tenant: TenantId,
        payload: Map<String, Any?>,
    ): HandleBacklogValuesResult {
        val measurement = read(payload)
        val results =
            CLASSES.map { alertClass ->
                val limits = thresholds.thresholds(tenant, measurement.entityRef, alertClass)
                val decision = BacklogEvaluation.decide(measurement, alertClass, limits)
                val kind =
                    when (decision.kind) {
                        BacklogDecisionKind.BREACHED -> {
                            val draft =
                                BacklogAlertDraft(
                                    tenant,
                                    alertClass,
                                    BACKLOG_ALERT_TRIGGER,
                                    measurement.entityRef,
                                    measurement.periodRef,
                                    clock.instant(),
                                )
                            if (store.raise(draft)) BacklogResultKind.RAISED else BacklogResultKind.ALREADY_OPEN
                        }

                        BacklogDecisionKind.WITHIN_THRESHOLD -> {
                            BacklogResultKind.WITHIN_THRESHOLD
                        }

                        BacklogDecisionKind.NOT_CONFIGURED -> {
                            BacklogResultKind.NOT_CONFIGURED
                        }
                    }
                BacklogClassResult(alertClass, kind, decision.breached, decision.notConfigured)
            }
        return HandleBacklogValuesResult(results)
    }

    private fun read(payload: Map<String, Any?>): BacklogMeasurement {
        val entityRef = text(payload, "entityRef", MAX_ENTITY_REF)
        val periodRef = text(payload, "periodRef", MAX_PERIOD_REF)
        val queueDepth = integer(payload, "queueDepth") ?: throw ContractViolation("queueDepth is required")
        val age = integer(payload, "oldestUnsentAgeSeconds")
        val rate = failureRate(payload)
        val unacknowledged =
            integer(payload, "unacknowledgedCount") ?: throw ContractViolation("unacknowledgedCount is required")
        return BacklogMeasurement(
            entityRef,
            periodRef,
            instant(payload, "measuredAt"),
            queueDepth,
            age,
            rate,
            unacknowledged,
        )
    }

    private fun text(
        payload: Map<String, Any?>,
        key: String,
        max: Int,
    ): String {
        val value = payload[key] as? String
        if (value.isNullOrBlank()) throw ContractViolation("$key is required")
        if (value.length > max) throw ContractViolation("$key is longer than $max")
        return value
    }

    /** Null when absent; Int or Long accepted, never negative. */
    private fun integer(
        payload: Map<String, Any?>,
        key: String,
    ): Long? {
        val value = payload[key] ?: return null
        val number =
            when (value) {
                is Int -> value.toLong()
                is Long -> value
                else -> throw ContractViolation("$key must be an integer")
            }
        if (number < 0) throw ContractViolation("$key must not be negative")
        return number
    }

    private fun failureRate(payload: Map<String, Any?>): Double {
        val number =
            (payload["failureRate"] as? Number)?.toDouble()
                ?: throw ContractViolation("failureRate is required and must be a number")
        return number.takeIf { it in 0.0..1.0 } ?: throw ContractViolation("failureRate must be within 0 to 1")
    }

    private fun instant(
        payload: Map<String, Any?>,
        key: String,
    ): Instant =
        when (val value = payload[key]) {
            is Instant -> {
                value
            }

            is String -> {
                runCatching { OffsetDateTime.parse(value).toInstant() }
                    .getOrElse { throw ContractViolation("$key must be a date-time") }
            }

            else -> {
                throw ContractViolation("$key is required")
            }
        }

    private companion object {
        val CLASSES = listOf(AlertClass.BACKLOG, AlertClass.MISSING_ACKNOWLEDGEMENT)
        const val MAX_ENTITY_REF = 64
        const val MAX_PERIOD_REF = 32
    }
}
