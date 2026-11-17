package com.dynokore.gst.access.application.consultant

import com.dynokore.gst.access.application.grant.GrantAudit
import com.dynokore.gst.access.application.grant.GrantAuditEntry
import com.dynokore.gst.access.application.grant.GrantRoleCommand
import com.dynokore.gst.access.application.grant.GrantRoleOutcome
import com.dynokore.gst.access.application.grant.GrantRoleUseCase
import com.dynokore.gst.access.application.review.VpAvailability
import com.dynokore.gst.access.domain.consultant.ConsultantGrantAsk
import com.dynokore.gst.access.domain.consultant.ConsultantGrantPeriod
import com.dynokore.gst.access.domain.consultant.ConsultantGrantRecord
import com.dynokore.gst.access.domain.consultant.ConsultantRequestStatus
import com.dynokore.gst.access.domain.grant.GranteeKind
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.Scope
import com.dynokore.gst.access.domain.grant.ValidityWindow
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.gst.access.domain.role.Role
import com.dynokore.platform.tenancykernel.TransactionRunner
import java.time.Clock
import java.time.ZoneOffset

/** The permission keys the two operations ask for, compared by equality (INV-Q-10027); no code compares a role. */
object ConsultantGrantKeys {
    val REQUEST = Permission("consultant-grant.request")

    /** The approval key: the existing `user.manage` (INV-Q-15794 A). */
    val APPROVE = Permission("user.manage")
}

/** Persistence of consultant grant requests under the bound tenant (row-level security). */
interface ConsultantGrantRequests {
    /** The pending request with the same tenant, identity and go-live date (the repeat key of the contract). */
    fun findPending(ask: ConsultantGrantAsk): ConsultantGrantRecord?

    /** Stores a new PENDING request for the bound tenant under a fresh reference. */
    fun insert(ask: ConsultantGrantAsk): ConsultantGrantRecord

    /** The request with [ref] locked until the transaction ends, or null (also for another tenant). */
    fun lock(ref: String): ConsultantGrantRecord?

    fun update(
        before: ConsultantGrantRecord,
        after: ConsultantGrantRecord,
    )
}

/** VP-69 as the platform reports it, read live on every decision. */
fun interface Vp69Availability {
    fun current(): VpAvailability
}

/** `requestConsultantGrant` (INV-REQ-908): a PENDING request, audited with the acting user ID. */
class RequestConsultantGrantUseCase(
    private val tx: TransactionRunner,
    private val requests: ConsultantGrantRequests,
    private val audit: GrantAudit,
) {
    /** A repeat of the same (tenant, identity, go-live date) while one is pending returns that request. */
    fun handle(ask: ConsultantGrantAsk): ConsultantGrantRecord =
        tx.inTransaction {
            requests.findPending(ask) ?: requests.insert(ask).also {
                audit.record(
                    GrantAuditEntry(
                        actor = ask.requestedBy.value,
                        action = "requestConsultantGrant",
                        scope = "tenant:${ask.tenant.value}",
                        reason = "request ${it.ref} for ${ask.identity.value}, go-live ${ask.goLiveDate}",
                        priorStateJson = null,
                    ),
                )
            }
        }
}

sealed interface ConsultantDecisionOutcome {
    data class Approved(
        val record: ConsultantGrantRecord,
    ) : ConsultantDecisionOutcome

    data class Denied(
        val record: ConsultantGrantRecord,
    ) : ConsultantDecisionOutcome

    /** No such request in the bound tenant. */
    data object NotFound : ConsultantDecisionOutcome

    /** The request is already decided (409). */
    data object NotPending : ConsultantDecisionOutcome

    /** The approver is the identity that made the request (INV-Q-9704). */
    data object SameActor : ConsultantDecisionOutcome

    /** More than the platform maximum, or not a positive number of days (400, INV-AC-2494). */
    data object PeriodOutOfRange : ConsultantDecisionOutcome

    /** VP-69 is planned and the period is not 30 days (400, INV-AC-2512). */
    data object PeriodNotAvailable : ConsultantDecisionOutcome

    /** go-live plus the period is not after the approval date (400). */
    data object EndsNotAfterApproval : ConsultantDecisionOutcome

    /** `grantRole` refused the grant; nothing was written. */
    data class GrantRefused(
        val outcome: GrantRoleOutcome,
    ) : ConsultantDecisionOutcome
}

/**
 * `decideConsultantGrant` (INV-REQ-908, INV-REQ-2465). The caller's key `user.manage` is decided by the caller of this
 * use case. APPROVE writes one grant of `implementation.consultant` through [GrantRoleUseCase], the writer of
 * `grantRole`, so AuthorityChanged and the grant audit entry are written in the same transaction; DENY writes no grant.
 * A refusal anywhere rolls back every write of the decision.
 */
class DecideConsultantGrantUseCase(
    private val tx: TransactionRunner,
    private val requests: ConsultantGrantRequests,
    private val grantRole: GrantRoleUseCase,
    private val vp69: Vp69Availability,
    private val audit: GrantAudit,
    private val clock: Clock,
) {
    private class Rollback(
        val outcome: ConsultantDecisionOutcome,
    ) : RuntimeException(null, null, false, false)

    fun handle(
        approver: IdentityRef,
        requestRef: String,
        approve: Boolean,
        periodDays: Int?,
    ): ConsultantDecisionOutcome =
        try {
            tx.inTransaction { decide(approver, requestRef, approve, periodDays) }
        } catch (refusal: Rollback) {
            refusal.outcome
        }

    private fun decide(
        approver: IdentityRef,
        requestRef: String,
        approve: Boolean,
        periodDays: Int?,
    ): ConsultantDecisionOutcome {
        val record = requests.lock(requestRef) ?: return ConsultantDecisionOutcome.NotFound
        return when {
            record.status != ConsultantRequestStatus.PENDING -> ConsultantDecisionOutcome.NotPending
            record.ask.requestedBy == approver -> ConsultantDecisionOutcome.SameActor
            approve -> approved(approver, record, periodDays)
            else -> denied(approver, record)
        }
    }

    private fun denied(
        approver: IdentityRef,
        record: ConsultantGrantRecord,
    ): ConsultantDecisionOutcome {
        val after = record.denied(approver)
        requests.update(record, after)
        record(approver, "decideConsultantGrant", record, "DENY")
        return ConsultantDecisionOutcome.Denied(after)
    }

    private fun approved(
        approver: IdentityRef,
        record: ConsultantGrantRecord,
        periodDays: Int?,
    ): ConsultantDecisionOutcome {
        val choice = ConsultantGrantPeriod.choose(periodDays, vp69.current() == VpAvailability.AVAILABLE)
        val today = clock.instant().atOffset(ZoneOffset.UTC).toLocalDate()
        val validTo =
            (choice as? ConsultantGrantPeriod.Choice.Days)?.let {
                ConsultantGrantPeriod.validTo(record.ask.goLiveDate, it.value)
            }
        return when {
            choice == ConsultantGrantPeriod.Choice.OutOfRange -> ConsultantDecisionOutcome.PeriodOutOfRange
            choice == ConsultantGrantPeriod.Choice.NotAvailable -> ConsultantDecisionOutcome.PeriodNotAvailable
            validTo == null || !validTo.isAfter(today) -> ConsultantDecisionOutcome.EndsNotAfterApproval
            else -> write(approver, record, today, validTo)
        }
    }

    private fun write(
        approver: IdentityRef,
        record: ConsultantGrantRecord,
        today: java.time.LocalDate,
        validTo: java.time.LocalDate,
    ): ConsultantDecisionOutcome {
        val outcome =
            grantRole.handle(
                GrantRoleCommand(
                    tenant = record.ask.tenant,
                    actor = approver.value,
                    identity = record.ask.identity,
                    granteeKind = GranteeKind.EXTERNAL,
                    roleWire = Role.IMPLEMENTATION_CONSULTANT.wire,
                    scope = Scope.Tenant(record.ask.tenant),
                    window =
                        ValidityWindow(
                            today.atStartOfDay(ZoneOffset.UTC).toInstant(),
                            validTo.atStartOfDay(ZoneOffset.UTC).toInstant(),
                        ),
                ),
            )
        val granted =
            outcome as? GrantRoleOutcome.Granted ?: throw Rollback(ConsultantDecisionOutcome.GrantRefused(outcome))
        val after = record.approved(approver, granted.grant.ref, validTo)
        requests.update(record, after)
        record(approver, "decideConsultantGrant", record, "APPROVE ${granted.grant.ref.value} until $validTo")
        return ConsultantDecisionOutcome.Approved(after)
    }

    private fun record(
        approver: IdentityRef,
        action: String,
        record: ConsultantGrantRecord,
        reason: String,
    ) = audit.record(
        GrantAuditEntry(
            actor = approver.value,
            action = action,
            scope = "tenant:${record.ask.tenant.value}",
            reason = "request ${record.ref}: $reason",
            priorStateJson = """{"ref":"${record.ref}","status":"${record.status.name}"}""",
        ),
    )
}
