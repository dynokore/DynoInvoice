package com.dynokore.gst.submission.adapters.http

import com.dynokore.gst.submission.application.compliance.ComplianceEntry
import com.dynokore.gst.submission.application.compliance.ComplianceMetricsConfig
import com.dynokore.gst.submission.application.compliance.ComplianceMetricsQuery
import com.dynokore.gst.submission.application.compliance.ComplianceMetricsRequest
import com.dynokore.gst.submission.application.compliance.ComplianceMetricsResult
import com.dynokore.gst.submission.application.compliance.ObligationRowSource
import com.dynokore.gst.submission.contract.api.MetricsApi
import com.dynokore.gst.submission.contract.model.ComplianceMetricsEntry
import com.dynokore.gst.submission.contract.model.ComplianceMetricsEntryCorrection
import com.dynokore.gst.submission.contract.model.ComplianceMetricsEntryCoverage
import com.dynokore.gst.submission.contract.model.ComplianceMetricsEntryDeadline
import com.dynokore.gst.submission.contract.model.ComplianceMetricsEntryResubmissionTrendInner
import com.dynokore.gst.submission.contract.model.ComplianceMetricsPage
import com.dynokore.gst.submission.contract.model.Ratio
import com.dynokore.gst.submission.domain.metrics.SignalState
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.Problems
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.logging.CorrelationContext
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.http.CurrentDeadline
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.LocalDate
import java.util.Base64
import java.util.UUID
import com.dynokore.gst.submission.domain.metrics.Ratio as DomainRatio

/** A refused compliance-metrics call: the closed service-runtime case, rendered once as the API-20 envelope. */
class ComplianceRefusal(
    val code: ClosedErrorCode,
) : RuntimeException(code.code)

/** ARC-19, CS-14: the one place a [ComplianceRefusal] becomes the API-20 envelope (SRT-001, -003, -004, -005). */
@RestControllerAdvice(assignableTypes = [ComplianceMetricsController::class])
class ComplianceRefusalAdvice {
    @ExceptionHandler(ComplianceRefusal::class)
    fun refuse(refusal: ComplianceRefusal): ResponseEntity<String> {
        val problem =
            Problems.closed(
                refusal.code,
                CorrelationContext.currentCorrelationId()?.value ?: UUID.randomUUID().toString(),
            )
        val headers = HttpHeaders()
        headers.set(HttpHeaders.CONTENT_TYPE, Problems.CONTENT_TYPE)
        return ResponseEntity.status(problem.status).headers(headers).body(problem.body)
    }
}

/**
 * `getComplianceMetrics` of the submission contract (INV-REQ-1069, INV-REQ-1070, INV-Q-12767 A). The permission is
 * decided by [ComplianceMetricsQuery] through `AuthorityReplica.decide`; this class reads no role name and holds no
 * permission string. The page follows the cursor paging of `listSubmissions` (API-18): the cursor is bound to the
 * filters that produced it, no offset and no total.
 */
@RestController
open class ComplianceMetricsController(
    private val query: ComplianceMetricsQuery,
    private val clock: Clock,
    private val requests: ObjectProvider<HttpServletRequest>,
) : MetricsApi {
    override fun getComplianceMetrics(
        entityRef: String?,
        periodRef: String?,
        limit: Int,
        cursor: String?,
    ): ResponseEntity<ComplianceMetricsPage> {
        val caller = caller()
        val after = validated(entityRef, periodRef, limit, cursor)
        refuseOutsideScope(caller, entityRef)
        val result =
            query.handle(
                ComplianceMetricsRequest(
                    tenant = tenantOf(caller),
                    principal = Principal(caller.actor, PrincipalKind.HUMAN),
                    entityRef = entityRef,
                    periodRef = periodRef,
                ),
                LocalDate.now(clock),
                CurrentDeadline.current() ?: error("no call deadline is bound"),
            )
        val entries =
            (result as? ComplianceMetricsResult.Allowed)?.entries
                ?: throw ComplianceRefusal(ClosedErrorCode.ACCESS_DENIED)
        return ResponseEntity.ok(page(entries, entityRef, periodRef, limit, after))
    }

    /** API-24 for the entity filter: an entityRef outside the token's current entity set is SRT-005 (the contract). */
    private fun refuseOutsideScope(
        caller: EndUserPrincipal,
        entityRef: String?,
    ) {
        if (entityRef != null && caller.entitySetCurrent && !caller.covers(EntityRef(entityRef))) {
            throw ComplianceRefusal(ClosedErrorCode.NOT_FOUND)
        }
    }

    private fun tenantOf(caller: EndUserPrincipal) =
        caller.tenant ?: throw ComplianceRefusal(ClosedErrorCode.UNAUTHENTICATED)

    /** A filter, limit or cursor that breaks the contract is SRT-001; returns the decoded cursor position. */
    private fun validated(
        entityRef: String?,
        periodRef: String?,
        limit: Int,
        cursor: String?,
    ): Pair<String, String>? {
        require400(entityRef == null || entityRef.isNotBlank())
        require400(periodRef == null || periodRef.isNotBlank())
        require400(limit in 1..MAX_LIMIT)
        return cursor?.let { Cursor.decode(it, entityRef, periodRef) }
    }

    private fun caller(): EndUserPrincipal =
        requests.getObject().getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal
            ?: throw ComplianceRefusal(ClosedErrorCode.UNAUTHENTICATED)

    private fun require400(valid: Boolean) {
        if (!valid) throw ComplianceRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
    }

    private fun page(
        entries: List<ComplianceEntry>,
        entityRef: String?,
        periodRef: String?,
        limit: Int,
        after: Pair<String, String>?,
    ): ComplianceMetricsPage {
        val ordered = entries.sortedWith(compareBy({ it.metrics.entityRef }, { it.metrics.periodRef }))
        val trendLabels =
            ordered
                .groupBy { it.metrics.entityRef }
                .mapValues { (_, own) -> own.map { it.metrics.periodRef } }
        val remaining =
            ordered.filter {
                after == null ||
                    compareValuesBy(positionOf(it), after, { p -> p.first }, { p -> p.second }) > 0
            }
        val pageEntries = remaining.take(limit)
        val last = pageEntries.lastOrNull()?.metrics
        val next =
            if (remaining.size > limit && last != null) {
                Cursor.encode(entityRef, periodRef, last.entityRef, last.periodRef)
            } else {
                null
            }
        return ComplianceMetricsPage(pageEntries.map { toItem(it, trendLabels.getValue(it.metrics.entityRef)) }, next)
    }

    private fun toItem(
        entry: ComplianceEntry,
        entityPeriods: List<String>,
    ): ComplianceMetricsEntry {
        val metrics = entry.metrics
        // The trend is the entity's last periods, oldest first; its labels are the same periods of the result.
        val labels = entityPeriods.takeLast(entry.resubmissionTrend.size)
        return ComplianceMetricsEntry(
            entityRef = metrics.entityRef,
            periodRef = metrics.periodRef,
            coverage =
                ComplianceMetricsEntryCoverage(
                    metrics.coverage.documentsWithoutSubmission,
                    metrics.coverage.breached,
                ),
            deadline =
                ComplianceMetricsEntryDeadline(
                    metrics.deadline.documentsPastDue,
                    metrics.deadline.hasResidualBacklog,
                    metrics.deadline.daysToNearestDeadline?.toInt(),
                ),
            correction =
                ComplianceMetricsEntryCorrection(
                    ratio(metrics.correction.resubmission),
                    ratio(metrics.correction.creditNote),
                    metrics.correction.manyVersionDocuments,
                ),
            resubmissionTrend =
                labels.zip(entry.resubmissionTrend).map { (label, value) ->
                    ComplianceMetricsEntryResubmissionTrendInner(label, ratio(value))
                },
            resubmissionSignal = signal(entry.resubmissionSignal),
        )
    }

    private fun positionOf(entry: ComplianceEntry) = entry.metrics.entityRef to entry.metrics.periodRef

    private fun ratio(value: DomainRatio) = Ratio(value.numerator, value.denominator)

    private fun signal(state: SignalState): ComplianceMetricsEntry.ResubmissionSignal =
        ComplianceMetricsEntry.ResubmissionSignal.valueOf(state.name)

    private companion object {
        const val MAX_LIMIT = 500
    }
}

/** An opaque cursor: the filters that produced it and the last (entityRef, periodRef) of the page (API-18). */
private object Cursor {
    private const val SEPARATOR = '\u0000'
    private const val FIELDS = 4
    private const val FILTER_ENTITY = 0
    private const val FILTER_PERIOD = 1
    private const val LAST_ENTITY = 2
    private const val LAST_PERIOD = 3

    fun encode(
        entityFilter: String?,
        periodFilter: String?,
        lastEntity: String,
        lastPeriod: String,
    ): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                listOf(entityFilter.orEmpty(), periodFilter.orEmpty(), lastEntity, lastPeriod)
                    .joinToString(SEPARATOR.toString())
                    .toByteArray(StandardCharsets.UTF_8),
            )

    /** The last (entityRef, periodRef); a cursor that does not decode, or was made for other filters, is SRT-001. */
    fun decode(
        cursor: String,
        entityFilter: String?,
        periodFilter: String?,
    ): Pair<String, String> {
        val parts =
            runCatching {
                String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(SEPARATOR)
            }.getOrNull()
        val valid =
            parts != null &&
                parts.size == FIELDS &&
                parts[FILTER_ENTITY] == entityFilter.orEmpty() &&
                parts[FILTER_PERIOD] == periodFilter.orEmpty() &&
                parts[LAST_ENTITY].isNotEmpty() &&
                parts[LAST_PERIOD].isNotEmpty()
        if (!valid) throw ComplianceRefusal(ClosedErrorCode.CONTRACT_VIOLATION)
        return parts[LAST_ENTITY] to parts[LAST_PERIOD]
    }
}

/** The threshold configuration of application.yaml as plain numbers (the bootstrap module has no domain dependency). */
data class ComplianceThresholds(
    val manyVersionsFrom: Int,
    val highNumerator: Int,
    val highDenominator: Int,
    val trendPeriods: Int,
)

/** The use case with its configuration made from [thresholds]; the ratio type of the domain stays in this module. */
fun complianceMetricsQuery(
    replica: AuthorityReplica,
    rows: ObligationRowSource,
    thresholds: ComplianceThresholds,
): ComplianceMetricsQuery =
    ComplianceMetricsQuery(
        replica,
        rows,
        ComplianceMetricsConfig(
            thresholds.manyVersionsFrom,
            DomainRatio(thresholds.highNumerator, thresholds.highDenominator),
            thresholds.trendPeriods,
        ),
    )
