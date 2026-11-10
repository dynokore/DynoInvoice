package com.dynokore.gst.controlPlane.application.schedule

import com.dynokore.gst.controlPlane.application.commercial.auditContext
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.schedule.CustodyHolder
import com.dynokore.gst.controlPlane.domain.schedule.PendingKind
import com.dynokore.gst.controlPlane.domain.schedule.PendingRequest
import com.dynokore.gst.controlPlane.domain.schedule.ScheduleRule
import com.dynokore.gst.controlPlane.domain.schedule.ScheduleState
import com.dynokore.gst.controlPlane.domain.schedule.TenantSchedule
import com.dynokore.platform.serviceruntime.operation.DeclaredOperation
import com.dynokore.platform.serviceruntime.operation.OperationAudit
import com.dynokore.platform.serviceruntime.operation.Reversibility
import java.time.Clock
import java.time.Instant

/** The refusal codes the runner carries; the controller maps each to its status. */
object ScheduleRefusal {
    /** 404: the tenant has no schedule, or the named scheduleRef is not its schedule. */
    const val NOT_FOUND = "SCHEDULE_NOT_FOUND"

    /** 403: the approver is the person who set the schedule or who requested the change (INV-Q-8095 option C). */
    const val SELF_APPROVAL = "SCHEDULE_SELF_APPROVAL"

    /** 409: the request names nothing that awaits approval, or the schedule is not APPROVED. */
    const val NOT_AWAITING = "SCHEDULE_NOT_AWAITING_APPROVAL"
    const val NOT_APPROVED = "SCHEDULE_NOT_APPROVED"

    /** 409: a time that was already carried out cannot be changed (INV-REQ-8100 behavior 4). */
    const val CARRIED_OUT = "SCHEDULE_TIME_CARRIED_OUT"

    /** 409 (the published approveTenantSchedule declares no 422): a pending change no longer holds at approval. */
    const val CHANGE_NOT_VALID_NOW = "SCHEDULE_CHANGE_NOT_VALID_NOW"

    /** 409: a request of the same kind is already pending, or the tenant already has a live schedule. */
    const val PENDING_EXISTS = "SCHEDULE_REQUEST_PENDING"
    const val SCHEDULE_EXISTS = "SCHEDULE_EXISTS"

    /** 422. */
    const val NO_TIME = "SCHEDULE_CHANGE_NAMES_NO_TIME"
}

/** The permission key of each operation, decided per request through [OperatorPermissions], never a role name. */
object SchedulePermission {
    const val SET = "tenant-schedule.set"
    const val CHANGE = "tenant-schedule.change"
    const val CANCEL = "tenant-schedule.cancel"
    const val READ = "tenant-schedule.read"
    const val APPROVE = "tenant-schedule.approve"
}

private const val SCOPE = "installation"

private fun scheduleAudit(
    schedule: TenantSchedule,
    request: RequestContext,
) = OperationAudit(
    SCOPE,
    "tenant=${schedule.tenantRef.value} contract=${schedule.contractReference.value}",
    null,
    auditContext(schedule.scheduleRef, request),
)

data class SetTenantScheduleCommand(
    val actor: ActorId,
    val tenantRef: CommercialRef,
    val contractReference: String?,
    val goLiveAt: Instant,
    val offboardingAt: Instant,
    val custodyHolder: CustodyHolder,
    val noticeWindowDays: Int?,
    val request: RequestContext,
)

/**
 * setTenantSchedule (INV-REQ-8100 behavior 1): the holder of `tenant-schedule.set` stores a schedule
 * AWAITING_APPROVAL with `setBy` the caller's principal reference. A schedule without a contract reference, with an
 * offboarding time not after the go-live time, or a go-live time not after the moment of setting is refused (422).
 * The repeat key is (tenantRef, contractReference): a repeat returns the original schedule.
 */
class SetTenantSchedule(
    private val schedules: TenantScheduleRepository,
    private val permissions: OperatorPermissions,
    private val clock: Clock,
    private val refs: () -> String,
) : DeclaredOperation<SetTenantScheduleCommand, TenantSchedule?, TenantSchedule> {
    override val operationId = "setTenantSchedule"
    override val reversibility = Reversibility.UNDOABLE
    override val audited = true

    override fun permits(
        actor: String,
        command: SetTenantScheduleCommand,
    ) = permissions.holds(actor, SchedulePermission.SET) && actor == command.actor.value

    override fun repeatKey(command: SetTenantScheduleCommand) =
        "${command.tenantRef.value}|${command.contractReference}"

    override fun load(command: SetTenantScheduleCommand) = schedules.find(command.tenantRef)

    override fun priorResult(
        command: SetTenantScheduleCommand,
        state: TenantSchedule?,
    ) = state?.takeIf { it.state != ScheduleState.CANCELLED && it.contractReference.value == command.contractReference }

    override fun unmetPrecondition(
        command: SetTenantScheduleCommand,
        state: TenantSchedule?,
    ): String? =
        when {
            command.contractReference.isNullOrBlank() -> {
                ScheduleRule.MISSING_CONTRACT
            }

            else -> {
                ScheduleRule.timesRefusal(command.goLiveAt, command.offboardingAt, clock.instant())
                    ?: ScheduleRefusal.SCHEDULE_EXISTS.takeIf {
                        state != null && state.state != ScheduleState.CANCELLED
                    }
            }
        }

    override fun perform(
        command: SetTenantScheduleCommand,
        state: TenantSchedule?,
    ): TenantSchedule {
        val schedule =
            TenantSchedule(
                tenantRef = command.tenantRef,
                scheduleRef = refs(),
                contractReference = CommercialRef(checkNotNull(command.contractReference)),
                state = ScheduleState.AWAITING_APPROVAL,
                goLiveAt = command.goLiveAt,
                offboardingAt = command.offboardingAt,
                setBy = command.actor.value,
                approvedBy = null,
                custodyHolder = command.custodyHolder,
                noticeWindowDays = command.noticeWindowDays,
                openedAt = null,
            )
        schedules.insert(schedule, clock.instant())
        return schedule
    }

    override fun audit(
        command: SetTenantScheduleCommand,
        state: TenantSchedule?,
        result: TenantSchedule,
    ) = scheduleAudit(result, command.request)
}

data class ApproveTenantScheduleCommand(
    val actor: ActorId,
    val tenantRef: CommercialRef,
    val target: Target,
    val request: RequestContext,
) {
    enum class Target { SCHEDULE, CHANGE, CANCELLATION }
}

/**
 * approveTenantSchedule (INV-REQ-8100 behavior 2, owner answers INV-Q-8095 option C and INV-Q-10127 A): the approver
 * holds `tenant-schedule.approve` (decided through the port per request) and is a different person from the one who
 * set the schedule and from the one who requested the change or cancellation, compared by principal reference with
 * equality. Both refusals are 403 and change nothing. A second approval of a schedule changes nothing.
 */
class ApproveTenantSchedule(
    private val schedules: TenantScheduleRepository,
    private val permissions: OperatorPermissions,
    private val clock: Clock,
) : DeclaredOperation<ApproveTenantScheduleCommand, TenantSchedule?, TenantSchedule> {
    override val operationId = "approveTenantSchedule"
    override val reversibility = Reversibility.COMPENSABLE
    override val audited = true

    override fun permits(
        actor: String,
        command: ApproveTenantScheduleCommand,
    ) = permissions.holds(actor, SchedulePermission.APPROVE) && actor == command.actor.value

    override fun repeatKey(command: ApproveTenantScheduleCommand) = "${command.tenantRef.value}|${command.target}"

    override fun load(command: ApproveTenantScheduleCommand) = schedules.find(command.tenantRef)

    override fun priorResult(
        command: ApproveTenantScheduleCommand,
        state: TenantSchedule?,
    ) = state?.takeIf {
        command.target == ApproveTenantScheduleCommand.Target.SCHEDULE && it.state != ScheduleState.AWAITING_APPROVAL
    }

    override fun unmetPrecondition(
        command: ApproveTenantScheduleCommand,
        state: TenantSchedule?,
    ): String? {
        if (state == null) return ScheduleRefusal.NOT_FOUND
        val actor = command.actor.value
        return when (command.target) {
            ApproveTenantScheduleCommand.Target.SCHEDULE -> {
                if (actor == state.setBy) ScheduleRefusal.SELF_APPROVAL else null
            }

            ApproveTenantScheduleCommand.Target.CHANGE -> {
                pendingRefusal(state, PendingKind.CHANGE, actor) ?: changeRefusal(state)
            }

            ApproveTenantScheduleCommand.Target.CANCELLATION -> {
                pendingRefusal(state, PendingKind.CANCELLATION, actor)
            }
        }
    }

    private fun pendingRefusal(
        state: TenantSchedule,
        kind: PendingKind,
        actor: String,
    ): String? {
        val pending = state.pendingOf(kind)
        return when {
            pending == null || state.state != ScheduleState.APPROVED -> ScheduleRefusal.NOT_AWAITING
            actor == state.setBy || actor == pending.requestedBy -> ScheduleRefusal.SELF_APPROVAL
            else -> null
        }
    }

    /** The change is re-checked when it takes effect: a go-live time carried out meanwhile, or now in the past. */
    private fun changeRefusal(state: TenantSchedule): String? {
        val change = checkNotNull(state.pendingChange)
        val goLive = change.goLiveAt
        return when {
            goLive != null && state.goLiveCarriedOut -> {
                ScheduleRefusal.CARRIED_OUT
            }

            else -> {
                ScheduleRule
                    .timesRefusal(
                        goLive ?: state.goLiveAt,
                        change.offboardingAt ?: state.offboardingAt,
                        clock.instant(),
                        goLiveChanged = goLive != null,
                    )?.let { ScheduleRefusal.CHANGE_NOT_VALID_NOW }
            }
        }
    }

    override fun perform(
        command: ApproveTenantScheduleCommand,
        state: TenantSchedule?,
    ): TenantSchedule {
        val current = checkNotNull(state)
        val approver = command.actor.value
        val next =
            when (command.target) {
                ApproveTenantScheduleCommand.Target.SCHEDULE -> {
                    current.copy(state = ScheduleState.APPROVED, approvedBy = approver)
                }

                ApproveTenantScheduleCommand.Target.CHANGE -> {
                    current.approvePending(PendingKind.CHANGE, approver)
                }

                ApproveTenantScheduleCommand.Target.CANCELLATION -> {
                    current.approvePending(PendingKind.CANCELLATION, approver)
                }
            }
        schedules.update(next)
        return next
    }

    override fun audit(
        command: ApproveTenantScheduleCommand,
        state: TenantSchedule?,
        result: TenantSchedule,
    ) = scheduleAudit(result, command.request)
}

data class ChangeTenantScheduleCommand(
    val actor: ActorId,
    val tenantRef: CommercialRef,
    val scheduleRef: String,
    val goLiveAt: Instant?,
    val offboardingAt: Instant?,
    val request: RequestContext,
)

/**
 * changeTenantSchedule (INV-REQ-8100 behavior 4, INV-AC-8103): stores a pending change of the times of an APPROVED
 * schedule. The approved schedule stays in force until the change is approved; a go-live time that was carried out
 * cannot be changed (409). The repeat key is (tenantRef, scheduleRef).
 */
class ChangeTenantSchedule(
    private val schedules: TenantScheduleRepository,
    private val permissions: OperatorPermissions,
    private val clock: Clock,
) : DeclaredOperation<ChangeTenantScheduleCommand, TenantSchedule?, TenantSchedule> {
    override val operationId = "changeTenantSchedule"
    override val reversibility = Reversibility.UNDOABLE
    override val audited = true

    override fun permits(
        actor: String,
        command: ChangeTenantScheduleCommand,
    ) = permissions.holds(actor, SchedulePermission.CHANGE) && actor == command.actor.value

    override fun repeatKey(command: ChangeTenantScheduleCommand) = "${command.tenantRef.value}|${command.scheduleRef}"

    override fun load(command: ChangeTenantScheduleCommand) = schedules.find(command.tenantRef)

    override fun priorResult(
        command: ChangeTenantScheduleCommand,
        state: TenantSchedule?,
    ) = state?.takeIf {
        it.pendingChange?.let { pending ->
            pending.goLiveAt == command.goLiveAt &&
                pending.offboardingAt == command.offboardingAt &&
                pending.requestedBy == command.actor.value
        } == true
    }

    override fun unmetPrecondition(
        command: ChangeTenantScheduleCommand,
        state: TenantSchedule?,
    ): String? =
        when {
            state == null || state.scheduleRef != command.scheduleRef -> {
                ScheduleRefusal.NOT_FOUND
            }

            state.state != ScheduleState.APPROVED -> {
                ScheduleRefusal.NOT_APPROVED
            }

            command.goLiveAt != null && state.goLiveCarriedOut -> {
                ScheduleRefusal.CARRIED_OUT
            }

            command.goLiveAt == null && command.offboardingAt == null -> {
                ScheduleRefusal.NO_TIME
            }

            state.pendingChange != null -> {
                ScheduleRefusal.PENDING_EXISTS
            }

            else -> {
                ScheduleRule.timesRefusal(
                    command.goLiveAt ?: state.goLiveAt,
                    command.offboardingAt ?: state.offboardingAt,
                    clock.instant(),
                    goLiveChanged = command.goLiveAt != null,
                )
            }
        }

    override fun perform(
        command: ChangeTenantScheduleCommand,
        state: TenantSchedule?,
    ): TenantSchedule {
        val next =
            checkNotNull(state).copy(
                pendingChange =
                    PendingRequest(PendingKind.CHANGE, command.goLiveAt, command.offboardingAt, command.actor.value),
            )
        schedules.update(next)
        return next
    }

    override fun audit(
        command: ChangeTenantScheduleCommand,
        state: TenantSchedule?,
        result: TenantSchedule,
    ) = scheduleAudit(result, command.request)
}

data class CancelTenantScheduleCommand(
    val actor: ActorId,
    val tenantRef: CommercialRef,
    val request: RequestContext,
)

/**
 * cancelTenantSchedule (INV-REQ-8100 behavior 4, INV-AC-8103): stores a pending cancellation of an APPROVED schedule;
 * the approved schedule stays in force until it is approved. The repeat key is the tenantRef.
 */
class CancelTenantSchedule(
    private val schedules: TenantScheduleRepository,
    private val permissions: OperatorPermissions,
) : DeclaredOperation<CancelTenantScheduleCommand, TenantSchedule?, TenantSchedule> {
    override val operationId = "cancelTenantSchedule"
    override val reversibility = Reversibility.IRREVERSIBLE
    override val audited = true

    override fun permits(
        actor: String,
        command: CancelTenantScheduleCommand,
    ) = permissions.holds(actor, SchedulePermission.CANCEL) && actor == command.actor.value

    override fun repeatKey(command: CancelTenantScheduleCommand) = command.tenantRef.value

    override fun load(command: CancelTenantScheduleCommand) = schedules.find(command.tenantRef)

    override fun priorResult(
        command: CancelTenantScheduleCommand,
        state: TenantSchedule?,
    ) = state?.takeIf { it.pendingCancellation?.requestedBy == command.actor.value }

    override fun unmetPrecondition(
        command: CancelTenantScheduleCommand,
        state: TenantSchedule?,
    ): String? =
        when {
            state == null -> ScheduleRefusal.NOT_FOUND
            state.state != ScheduleState.APPROVED -> ScheduleRefusal.NOT_APPROVED
            state.pendingCancellation != null -> ScheduleRefusal.PENDING_EXISTS
            else -> null
        }

    override fun perform(
        command: CancelTenantScheduleCommand,
        state: TenantSchedule?,
    ): TenantSchedule {
        val next =
            checkNotNull(state).copy(
                pendingCancellation =
                    PendingRequest(PendingKind.CANCELLATION, null, null, command.actor.value),
            )
        schedules.update(next)
        return next
    }

    override fun audit(
        command: CancelTenantScheduleCommand,
        state: TenantSchedule?,
        result: TenantSchedule,
    ) = scheduleAudit(result, command.request)
}

/** getTenantSchedule: the current schedule of a tenant, or null. */
class GetTenantSchedule(
    private val schedules: TenantScheduleRepository,
    private val permissions: OperatorPermissions,
) {
    /** Whether [actor] holds the read key [SchedulePermission.READ]. */
    fun permits(actor: String) = permissions.holds(actor, SchedulePermission.READ)

    fun get(tenantRef: CommercialRef): TenantSchedule? = schedules.find(tenantRef)
}
