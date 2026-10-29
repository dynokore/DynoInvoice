package com.dynokore.gst.submission.application.compliance

import com.dynokore.gst.submission.domain.metrics.ComplianceMetrics
import com.dynokore.gst.submission.domain.metrics.ObligationRow
import com.dynokore.gst.submission.domain.metrics.PeriodMetrics
import com.dynokore.gst.submission.domain.metrics.Ratio
import com.dynokore.gst.submission.domain.metrics.ResubmissionSignal
import com.dynokore.gst.submission.domain.metrics.SignalState
import com.dynokore.platform.serviceruntime.replica.AuthorityDecision
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.LocalDate

/** The scoped permission key of the compliance-metrics read (INV-REQ-1069, owner answer INV-Q-12767 A). */
object ComplianceActions {
    const val VIEW = "compliance-metrics.view"
}

/** Reads the obligation rows of one tenant; a null filter selects all (the register adapter is a later task). */
fun interface ObligationRowSource {
    fun rows(
        tenant: TenantId,
        entityRef: String?,
        periodRef: String?,
    ): List<ObligationRow>
}

/** Configuration values, no defaults in code: they come from application.yaml (thresholds are configuration). */
data class ComplianceMetricsConfig(
    val manyVersionsFrom: Int,
    val highAtOrAbove: Ratio,
    val trendPeriods: Int,
)

/** The call: [principal] comes from the call context; a null filter is not applied. */
data class ComplianceMetricsRequest(
    val tenant: TenantId,
    val principal: Principal,
    val entityRef: String?,
    val periodRef: String?,
)

/** One (entity, period) value with the entity's resubmission trend and signal (INV-REQ-1070). */
data class ComplianceEntry(
    val metrics: PeriodMetrics,
    val resubmissionTrend: List<Ratio>,
    val resubmissionSignal: SignalState,
)

sealed interface ComplianceMetricsResult {
    data class Allowed(
        val entries: List<ComplianceEntry>,
    ) : ComplianceMetricsResult

    data class Forbidden(
        val reason: DecisionReason,
    ) : ComplianceMetricsResult
}

/**
 * Reads the compliance metrics behind [ComplianceActions.VIEW] (INV-REQ-1069, INV-REQ-1070). The permission is
 * decided by the replica alone; no role name is read. Pure of clock, SQL and metric registry: `today` is a parameter.
 */
class ComplianceMetricsQuery(
    private val replica: AuthorityReplica,
    private val rows: ObligationRowSource,
    private val config: ComplianceMetricsConfig,
) {
    fun handle(
        query: ComplianceMetricsRequest,
        today: LocalDate,
        deadline: Deadline,
    ): ComplianceMetricsResult {
        val decision = decide(query, deadline)
        if (!decision.allowed || decision.reason == DecisionReason.AUTHORITY_UNAVAILABLE) {
            return ComplianceMetricsResult.Forbidden(decision.reason)
        }
        val read = rows.rows(query.tenant, query.entityRef, query.periodRef)
        val report = ComplianceMetrics.compute(read, today, config.manyVersionsFrom)
        val trends =
            report.periods
                .groupBy { it.entityRef }
                .mapValues { (_, periods) ->
                    periods
                        .sortedBy { it.periodRef }
                        .map { it.correction.resubmission }
                        .takeLast(config.trendPeriods)
                }
        return ComplianceMetricsResult.Allowed(
            report.periods.map {
                val trend = trends.getValue(it.entityRef)
                ComplianceEntry(it, trend, ResubmissionSignal.classify(trend, config.highAtOrAbove))
            },
        )
    }

    /** A named entity is decided on its grants; without one the read is tenant-wide (INV-Q-11688 A). */
    private fun decide(
        query: ComplianceMetricsRequest,
        deadline: Deadline,
    ): AuthorityDecision =
        query.entityRef
            ?.let {
                replica.decide(
                    AuthorizationRequest(query.tenant, query.principal, it, ComplianceActions.VIEW),
                    deadline,
                )
            }
            ?: replica.decideTenant(query.tenant, query.principal, ComplianceActions.VIEW)
}
