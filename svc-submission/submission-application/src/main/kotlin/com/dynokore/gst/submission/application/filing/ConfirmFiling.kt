package com.dynokore.gst.submission.application.filing

import com.dynokore.gst.submission.application.gate.GateModes
import com.dynokore.gst.submission.application.gate.LatestChecks
import com.dynokore.gst.submission.application.readiness.ReleasedOverrideChecks
import com.dynokore.gst.submission.domain.filing.FiledFact
import com.dynokore.gst.submission.domain.filing.FilingConflict
import com.dynokore.gst.submission.domain.filing.GoverningFiling
import com.dynokore.gst.submission.domain.gate.FilingGate
import com.dynokore.gst.submission.domain.gate.Refuse
import com.dynokore.platform.serviceruntime.replica.AuthorityReplica
import com.dynokore.platform.serviceruntime.replica.AuthorizationRequest
import com.dynokore.platform.serviceruntime.replica.DecisionReason
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.serviceruntime.replica.PrincipalKind
import com.dynokore.platform.serviceruntime.resilience.Deadline
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.time.LocalDate

/** The catalogue keys of `confirmFiling`: the person key and the machine key (both exist; no role name here). */
object ConfirmFilingActions {
    const val PERSON = "filing.confirm"
    const val MACHINE = "machine.filing.confirm"
}

/** The call; [filedAt] is a calendar date (the HTTP binding converts the contract instant before the call). */
data class ConfirmFilingRequest(
    val tenant: TenantId,
    val principal: Principal,
    val entityRef: String,
    val periodRef: String,
    val filedAt: LocalDate,
    val acknowledgementRef: String,
    val portalRef: String?,
    val correction: Boolean,
)

sealed interface ConfirmFilingResult {
    data class Recorded(
        val governingFiledAt: LocalDate,
        val conflict: FilingConflict?,
        val confirmedAt: Instant,
    ) : ConfirmFilingResult

    data class Forbidden(
        val reason: DecisionReason,
    ) : ConfirmFilingResult

    data object NoDueDate : ConfirmFilingResult

    data class GateRefused(
        val code: String,
    ) : ConfirmFilingResult
}

/**
 * Confirms a filing (INV-REQ-917). Order: permission (one decision point, `AuthorityReplica.decide`, for every caller
 * kind), due date, gate, append; a refusal appends nothing. Reads no clock: `confirmedAt` is the `recordedAt` the store
 * stamped on the stored fact equal to the request, so a repeat answers the first call's value.
 */
class ConfirmFiling(
    private val replica: AuthorityReplica,
    private val dueDates: FilingDueDates,
    private val gateModes: GateModes,
    private val latestChecks: LatestChecks,
    private val releasedOverrides: ReleasedOverrideChecks,
    private val facts: FilingFactStore,
) {
    fun handle(
        request: ConfirmFilingRequest,
        deadline: Deadline,
    ): ConfirmFilingResult {
        refusal(request, deadline)?.let { return it }

        facts.append(
            NewFilingFact(
                entityRef = request.entityRef,
                periodRef = request.periodRef,
                filedAt = request.filedAt,
                acknowledgementRef = request.acknowledgementRef,
                portalRef = request.portalRef,
                correction = request.correction,
                recordedBy = request.principal.id,
            ),
        )
        val stored = facts.facts(request.entityRef, request.periodRef)
        val own =
            stored.firstOrNull {
                it.filedAt == request.filedAt &&
                    it.acknowledgementRef == request.acknowledgementRef &&
                    it.correction == request.correction
            } ?: error("The appended filing fact is not readable")
        val governing =
            checkNotNull(GoverningFiling.of(stored.map { FiledFact(it.filedAt, it.correction) })) {
                "A period with a stored fact has a governing date"
            }
        return ConfirmFilingResult.Recorded(governing.governingFiledAt, governing.conflict, own.recordedAt)
    }

    /** The first refusal in the order permission, due date, gate; null when the filing may be recorded. */
    private fun refusal(
        request: ConfirmFilingRequest,
        deadline: Deadline,
    ): ConfirmFilingResult? {
        val decision =
            replica.decide(
                AuthorizationRequest(request.tenant, request.principal, request.entityRef, actionOf(request.principal)),
                deadline,
            )
        return when {
            !decision.allowed || decision.reason == DecisionReason.AUTHORITY_UNAVAILABLE -> {
                ConfirmFilingResult.Forbidden(decision.reason)
            }

            !hasDueDate(request) -> {
                ConfirmFilingResult.NoDueDate
            }

            else -> {
                gateRefusal(request)
            }
        }
    }

    private fun gateRefusal(request: ConfirmFilingRequest): ConfirmFilingResult.GateRefused? {
        val latest = latestChecks.latest(request.entityRef, request.periodRef)
        val released = releasedOverrides.releasedCheckIds(request.entityRef, request.periodRef)
        val overridden = latest?.checkId?.takeIf { it in released }
        val gate = FilingGate.decide(gateModes.modeOf(request.entityRef), latest, overridden)
        return (gate as? Refuse)?.let { ConfirmFilingResult.GateRefused(it.code) }
    }

    private fun actionOf(principal: Principal): String =
        if (principal.kind == PrincipalKind.MACHINE) ConfirmFilingActions.MACHINE else ConfirmFilingActions.PERSON

    /** No date and more than one date both refuse. */
    private fun hasDueDate(request: ConfirmFilingRequest): Boolean =
        try {
            dueDates.dueDateOf(request.entityRef, request.periodRef) != null
        } catch (_: AmbiguousDueDateException) {
            false
        }
}
