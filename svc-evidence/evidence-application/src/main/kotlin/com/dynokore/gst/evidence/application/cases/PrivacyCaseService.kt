package com.dynokore.gst.evidence.application.cases

import com.dynokore.gst.evidence.domain.cases.CaseClosure
import com.dynokore.gst.evidence.domain.cases.CaseKind
import com.dynokore.gst.evidence.domain.cases.CaseOutcome
import com.dynokore.gst.evidence.domain.cases.CaseRecord
import com.dynokore.gst.evidence.domain.cases.CaseStatus
import com.dynokore.gst.evidence.domain.cases.PrivacyCaseFacts
import com.dynokore.gst.evidence.domain.cases.RequestKind
import com.dynokore.platform.calendarandperiods.responseDeadline
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** `OpenPrivacyCaseRequest` of the evidence contract. */
data class OpenPrivacyCaseCommand(
    val subjectRef: String,
    val requestKind: RequestKind,
    val receivedOn: LocalDate,
    val requestReference: String,
)

/** The result of `open`. */
sealed interface OpenResult {
    val record: CaseRecord

    data class Opened(
        override val record: CaseRecord,
    ) : OpenResult

    data class Replayed(
        override val record: CaseRecord,
    ) : OpenResult
}

/** The result of `close`. */
sealed interface CloseResult {
    data class Closed(
        val record: CaseRecord,
    ) : CloseResult

    data class Replayed(
        val record: CaseRecord,
    ) : CloseResult

    data object NotFound : CloseResult
}

/** `receivedOn` is later than the day of the call. */
class ReceivedInFuture(
    val receivedOn: LocalDate,
) : RuntimeException("receivedOn $receivedOn is after today")

/** The request reference exists with another `subjectRef`, `requestKind` or `receivedOn`. */
class ReferenceConflict(
    val requestReference: String,
) : RuntimeException("requestReference is already used with other data")

/** The case is a SECURITY record. */
class NotAPrivacyCase(
    val caseId: UUID,
) : RuntimeException("case $caseId is not a privacy case")

/** The case is CLOSED with another outcome or basis. */
class CloseConflict(
    val caseId: UUID,
) : RuntimeException("case $caseId is closed with another outcome or basis")

/** The privacy case use cases. Each asks the authorizer first; a refusal throws before any store call. */
class PrivacyCaseService(
    private val store: PrivacyCaseStore,
    private val authorizer: CaseAuthorizer,
    private val policy: PolicyValues,
    private val ids: () -> UUID,
    private val clock: Clock,
) {
    fun open(
        tenant: TenantId,
        principal: Principal,
        command: OpenPrivacyCaseCommand,
        today: LocalDate,
    ): OpenResult {
        authorizer.require(tenant, principal, CasePermissions.PRIVACY_REQUEST_HANDLE)
        if (command.receivedOn.isAfter(today)) throw ReceivedInFuture(command.receivedOn)
        val existing = store.findByRequestReference(tenant, command.requestReference)
        if (existing != null) {
            val facts = existing.privacy
            val same =
                facts != null &&
                    existing.subjectRef == command.subjectRef &&
                    facts.requestKind == command.requestKind &&
                    facts.receivedOn == command.receivedOn
            if (!same) throw ReferenceConflict(command.requestReference)
            return OpenResult.Replayed(existing)
        }
        val packDays = policy.packResponseDays()
        val tenantDays = policy.tenantResponseDays(tenant, today)
        val deadline = responseDeadline(command.receivedOn, packDays, tenantDays)
        val record =
            CaseRecord(
                caseId = ids(),
                kind = CaseKind.PRIVACY,
                status = CaseStatus.OPEN,
                subjectRef = command.subjectRef,
                openedAt = clock.instant(),
                security = null,
                privacy =
                    PrivacyCaseFacts(
                        requestKind = command.requestKind,
                        requestReference = command.requestReference,
                        receivedOn = command.receivedOn,
                        responseDeadline = deadline,
                        deadlinePeriodDays = tenantDays ?: packDays,
                        closure = null,
                        noticePromptedAt = null,
                    ),
            )
        store.insert(tenant, record)
        return OpenResult.Opened(record)
    }

    fun close(
        tenant: TenantId,
        principal: Principal,
        caseId: UUID,
        outcome: CaseOutcome,
        basis: String,
    ): CloseResult {
        authorizer.require(tenant, principal, CasePermissions.PRIVACY_REQUEST_HANDLE)
        val closure = CaseClosure(clock.instant(), outcome, basis)
        val existing = store.find(tenant, caseId)
        if (existing?.kind == CaseKind.SECURITY) throw NotAPrivacyCase(caseId)
        return if (existing == null) CloseResult.NotFound else closeExisting(tenant, existing, closure)
    }

    private fun closeExisting(
        tenant: TenantId,
        existing: CaseRecord,
        closure: CaseClosure,
    ): CloseResult {
        val facts = existing.privacy ?: throw NotAPrivacyCase(existing.caseId)
        if (existing.status != CaseStatus.CLOSED) {
            val closed = existing.copy(status = CaseStatus.CLOSED, privacy = facts.copy(closure = closure))
            store.update(tenant, closed)
            return CloseResult.Closed(closed)
        }
        val first = requireNotNull(facts.closure) { "a CLOSED case holds its closure" }
        if (first.outcome != closure.outcome || first.basis != closure.basis) throw CloseConflict(existing.caseId)
        return CloseResult.Replayed(existing)
    }
}
