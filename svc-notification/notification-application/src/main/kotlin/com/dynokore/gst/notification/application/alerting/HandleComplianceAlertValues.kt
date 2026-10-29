package com.dynokore.gst.notification.application.alerting

import com.dynokore.gst.notification.domain.alerting.AlertClass
import com.dynokore.gst.notification.domain.alerting.ComplianceAlert
import com.dynokore.gst.notification.domain.alerting.ComplianceAlertDecision
import com.dynokore.gst.notification.domain.alerting.ComplianceAlertDraft
import com.dynokore.gst.notification.domain.alerting.ComplianceValues
import com.dynokore.gst.notification.domain.alerting.PolicyTier
import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/** The tiers of the entity's alert policy for one class (INV-REQ-1113); the daysToDue values are data. */
interface PolicyTiers {
    fun tiers(
        tenant: TenantId,
        entityRef: String,
        alertClass: AlertClass,
    ): List<PolicyTier>
}

/** Raises one alert from a draft; recipients and channels come from the entity's policy behind it. */
interface AlertSink {
    fun raise(draft: ComplianceAlertDraft)
}

/** The inbox of processed event identifiers (INV-REQ-1222). */
interface ProcessedEvents {
    /** True exactly once per [eventId]. */
    fun firstTime(eventId: String): Boolean
}

/** The payload cannot be handled; the message goes to the dead letter at once. */
class ContractViolation(
    message: String,
) : RuntimeException(message)

sealed class HandleComplianceAlertValuesResult {
    data object Ignored : HandleComplianceAlertValuesResult()

    data class Raised(
        val count: Int,
    ) : HandleComplianceAlertValuesResult()

    data class Withheld(
        val reason: String,
    ) : HandleComplianceAlertValuesResult()
}

/** Handles ComplianceAlertValuesMeasured (INV-Q-12767 A, INV-REQ-1069, INV-REQ-1113) with [ComplianceAlert]. */
class HandleComplianceAlertValues(
    private val processed: ProcessedEvents,
    private val policy: PolicyTiers,
    private val sink: AlertSink,
) {
    fun handle(
        eventId: String,
        tenant: TenantId,
        payload: Map<String, Any?>,
    ): HandleComplianceAlertValuesResult {
        if (!processed.firstTime(eventId)) return HandleComplianceAlertValuesResult.Ignored
        val values = read(payload)
        val tiers = policy.tiers(tenant, values.entityRef, AlertClass.DEADLINE)
        return when (val decision = ComplianceAlert.decide(values, tiers)) {
            is ComplianceAlertDecision.Raise -> {
                decision.drafts.forEach { sink.raise(it) }
                HandleComplianceAlertValuesResult.Raised(decision.drafts.size)
            }

            is ComplianceAlertDecision.Withhold -> {
                HandleComplianceAlertValuesResult.Withheld(decision.reason.name)
            }
        }
    }

    private fun read(payload: Map<String, Any?>): ComplianceValues {
        val without = int(payload, "documentsWithoutSubmission")
        if (without < 1) throw ContractViolation("documentsWithoutSubmission must be at least 1")
        return ComplianceValues(
            entityRef = text(payload, "entityRef"),
            periodRef = text(payload, "periodRef"),
            measuredOn = date(payload, "measuredOn"),
            documentsWithoutSubmission = without,
            documentsPastDue = int(payload, "documentsPastDue"),
            daysToNearestDeadline = payload["daysToNearestDeadline"]?.let { number("daysToNearestDeadline", it) },
        )
    }

    private fun text(
        payload: Map<String, Any?>,
        key: String,
    ): String {
        val value = payload[key] as? String
        if (value.isNullOrBlank()) throw ContractViolation("$key is required")
        return value
    }

    private fun int(
        payload: Map<String, Any?>,
        key: String,
    ): Int {
        val value = payload[key] ?: throw ContractViolation("$key is required")
        val number = number(key, value)
        if (number < 0) throw ContractViolation("$key must not be negative")
        return number
    }

    private fun number(
        key: String,
        value: Any,
    ): Int = (value as? Int) ?: throw ContractViolation("$key must be an integer")

    private fun date(
        payload: Map<String, Any?>,
        key: String,
    ): LocalDate =
        when (val value = payload[key]) {
            is LocalDate -> {
                value
            }

            is String -> {
                runCatching {
                    LocalDate.parse(
                        value,
                    )
                }.getOrElse { throw ContractViolation("$key must be a date") }
            }

            else -> {
                throw ContractViolation("$key is required")
            }
        }
}
