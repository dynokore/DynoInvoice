package com.dynokore.gst.submission.application.drain

import com.dynokore.gst.submission.application.pack.MarginUnit
import com.dynokore.gst.submission.application.pack.PackMargin
import com.dynokore.gst.submission.application.register.PeriodDueDates
import com.dynokore.gst.submission.domain.drain.DrainItem
import com.dynokore.gst.submission.domain.drain.DrainOrder
import com.dynokore.gst.submission.domain.drain.DrainScope
import com.dynokore.gst.submission.domain.drain.RankedItem
import com.dynokore.gst.submission.domain.drain.Tier
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.LocalDate

/** The call of both operations: [principal] is the verified caller; the acting user id is [Principal.id]. */
data class DrainRequest(
    val tenant: TenantId,
    val principal: Principal,
    val entityRef: String,
    val periodRef: String,
) {
    init {
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(periodRef.isNotBlank()) { "periodRef must not be blank" }
    }

    val scope: DrainScope get() = DrainScope(entityRef, periodRef)
}

/** The one selection of a drain and of its preview; [Refused] writes and despatches nothing. */
sealed interface DrainSelected {
    data class Ranked(
        val items: List<RankedItem>,
    ) : DrainSelected

    /** A null read, a missing due date or an unavailable margin (fail closed, answer 20694 case 4). */
    data object Refused : DrainSelected
}

class DrainSelection(
    private val backlog: DrainBacklog,
    private val dueDates: PeriodDueDates,
    private val margins: SafetyMargins,
    private val clock: Clock,
) {
    fun select(request: DrainRequest): DrainSelected {
        val today = LocalDate.now(clock)
        val queued = backlog.queued(request.tenant) ?: return DrainSelected.Refused
        val windows = HashMap<String, Long?>()
        val due = HashMap<Pair<String, String>, LocalDate?>()
        val items = ArrayList<DrainItem>(queued.size)
        for (q in queued) {
            val dueDate =
                due.getOrPut(
                    q.entityRef to q.periodRef,
                ) { dueDates.find(request.tenant, q.entityRef, q.periodRef) }
            val days = windows.getOrPut(q.entityRef) { marginDays(request.tenant, q.entityRef, today) }
            if (dueDate == null || days == null) return DrainSelected.Refused
            items +=
                DrainItem(q.docUuid, q.version, q.entityRef, q.periodRef, dueDate, dueDate.minusDays(days), q.queuedAt)
        }
        return DrainSelected.Ranked(DrainOrder.rank(items, request.scope, today))
    }

    /** The tier a version of the drained entity and period has now; null when no due date or margin is known. */
    fun tierOfDrained(
        request: DrainRequest,
        docUuid: String,
        version: Int,
        queuedAt: java.time.Instant,
    ): Tier? {
        val today = LocalDate.now(clock)
        val dueDate = dueDates.find(request.tenant, request.entityRef, request.periodRef) ?: return null
        val days = marginDays(request.tenant, request.entityRef, today) ?: return null
        val item =
            DrainItem(
                docUuid,
                version,
                request.entityRef,
                request.periodRef,
                dueDate,
                dueDate.minusDays(days),
                queuedAt,
            )
        return DrainOrder.rank(listOf(item), request.scope, today).single().tier
    }

    private fun marginDays(
        tenant: TenantId,
        entityRef: String,
        today: LocalDate,
    ): Long? =
        when (val answer = margins.effective(tenant, entityRef, today)) {
            is SafetyMarginAnswer.Known -> wholeDays(answer.margin)
            SafetyMarginAnswer.Unavailable -> null
        }

    /** HOURS round up, so the window never opens later than the margin says. */
    private fun wholeDays(margin: PackMargin): Long =
        when (margin.unit) {
            MarginUnit.DAYS -> margin.value.toLong()
            MarginUnit.HOURS -> (margin.value + HOURS_PER_DAY - 1L) / HOURS_PER_DAY
        }

    private companion object {
        const val HOURS_PER_DAY = 24
    }
}

/** The outcome of the authorization of a drain or its preview. */
internal fun denial(
    authority: DrainAuthority,
    request: DrainRequest,
    allowedKeyOf: (PrincipalKind) -> String?,
    deadline: Deadline,
): DecisionReason? {
    val key = allowedKeyOf(request.principal.kind) ?: return DecisionReason.NO_GRANT
    val decision =
        authority.decide(
            AuthorizationRequest(request.tenant, request.principal, request.entityRef, key),
            deadline,
        )
    return if (!decision.allowed || decision.reason == DecisionReason.AUTHORITY_UNAVAILABLE) decision.reason else null
}

/** The key of the drain by principal kind. */
internal fun drainKey(kind: PrincipalKind): String =
    if (kind == PrincipalKind.MACHINE) DrainActions.MACHINE else DrainActions.PERSON

/** The key of the preview: the person key only; a MACHINE principal has none. */
internal fun previewKey(kind: PrincipalKind): String? = if (kind == PrincipalKind.MACHINE) null else DrainActions.PERSON
