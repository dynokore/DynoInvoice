package com.dynokore.gst.submission.application.compliance

import com.dynokore.gst.submission.domain.metrics.MetricsReport
import java.time.LocalDate

/**
 * The values of one `ComplianceAlertValuesMeasured` event for an (entity, period) pair with a non-zero coverage
 * (INV-REQ-1069). No metric label; the escalation by proximity is decided by notification-svc (INV-REQ-1113).
 */
data class AlertValue(
    val entityRef: String,
    val periodRef: String,
    val measuredOn: LocalDate,
    val documentsWithoutSubmission: Int,
    val documentsPastDue: Int,
    val daysToNearestDeadline: Int?,
)

object ComplianceAlertValues {
    /** One value per period with a document without submission; fields copied, never compared with a threshold. */
    fun derive(
        report: MetricsReport,
        measuredOn: LocalDate,
    ): List<AlertValue> =
        report.periods
            .filter { it.coverage.documentsWithoutSubmission >= 1 }
            .map {
                AlertValue(
                    entityRef = it.entityRef,
                    periodRef = it.periodRef,
                    measuredOn = measuredOn,
                    documentsWithoutSubmission = it.coverage.documentsWithoutSubmission,
                    documentsPastDue = it.deadline.documentsPastDue,
                    daysToNearestDeadline = it.deadline.daysToNearestDeadline?.let(Math::toIntExact),
                )
            }
}
