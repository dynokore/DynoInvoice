package com.dynokore.gst.controlPlane.adapters.http

import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.schedule.ApproveTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.ApproveTenantScheduleCommand
import com.dynokore.gst.controlPlane.application.schedule.CancelTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.CancelTenantScheduleCommand
import com.dynokore.gst.controlPlane.application.schedule.ChangeTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.ChangeTenantScheduleCommand
import com.dynokore.gst.controlPlane.application.schedule.GetTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.ScheduleRefusal
import com.dynokore.gst.controlPlane.application.schedule.SetTenantSchedule
import com.dynokore.gst.controlPlane.application.schedule.SetTenantScheduleCommand
import com.dynokore.gst.controlPlane.contract.model.ApproveTenantScheduleRequest
import com.dynokore.gst.controlPlane.contract.model.ChangeTenantScheduleRequest
import com.dynokore.gst.controlPlane.contract.model.SetTenantScheduleRequest
import com.dynokore.gst.controlPlane.contract.model.TenantScheduleResponse
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.gst.controlPlane.domain.schedule.CustodyHolder
import com.dynokore.gst.controlPlane.domain.schedule.ScheduleRule
import com.dynokore.gst.controlPlane.domain.schedule.TenantSchedule
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.operation.OperationOutcome
import com.dynokore.platform.serviceruntime.operation.OperationRunner
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.time.ZoneOffset
import java.util.UUID

/** The five tenant schedule operations of the commercial tag (INV-REQ-8100), called by [CommercialController]. */
@Suppress("LongParameterList") // one collaborator per operation (TPL-08)
class TenantScheduleEndpoint(
    private val principals: EndUserPrincipalResolver,
    private val platform: PlatformTenantBinding,
    private val transactions: TransactionRunner,
    private val runner: OperationRunner,
    private val set: SetTenantSchedule,
    private val approve: ApproveTenantSchedule,
    private val change: ChangeTenantSchedule,
    private val cancel: CancelTenantSchedule,
    private val get: GetTenantSchedule,
) {
    fun set(
        tenantRef: String,
        idempotencyKey: UUID,
        body: SetTenantScheduleRequest,
    ): ResponseEntity<TenantScheduleResponse> {
        val caller = OperatorCaller.of(principals)
        val actor = caller.principal.actor
        val command =
            caller.commandOf {
                SetTenantScheduleCommand(
                    ActorId(actor),
                    CommercialRef(tenantRef),
                    body.contractReference,
                    body.goLiveAt.toInstant(),
                    body.offboardingAt.toInstant(),
                    CustodyHolder(
                        body.retentionCustodyHolder.name,
                        body.retentionCustodyHolder.contactEmail,
                        body.retentionCustodyHolder.contactMobile,
                    ),
                    body.noticeWindowDays,
                    RequestContext(caller.correlationId, idempotencyKey.toString()),
                )
            }
        val outcome = platform.bindPlatform(actor) { runner.run(set, actor, command) }
        return ResponseEntity.status(HttpStatus.CREATED).body(responseOf(result(outcome, caller)))
    }

    fun approve(
        tenantRef: String,
        idempotencyKey: UUID,
        body: ApproveTenantScheduleRequest,
    ): ResponseEntity<TenantScheduleResponse> {
        val caller = OperatorCaller.of(principals)
        val actor = caller.principal.actor
        val command =
            caller.commandOf {
                ApproveTenantScheduleCommand(
                    ActorId(actor),
                    CommercialRef(tenantRef),
                    ApproveTenantScheduleCommand.Target.valueOf(body.target.name),
                    RequestContext(caller.correlationId, idempotencyKey.toString()),
                )
            }
        val outcome = platform.bindPlatform(actor) { runner.run(approve, actor, command) }
        return ResponseEntity.ok(responseOf(result(outcome, caller)))
    }

    fun change(
        tenantRef: String,
        idempotencyKey: UUID,
        body: ChangeTenantScheduleRequest,
    ): ResponseEntity<TenantScheduleResponse> {
        val caller = OperatorCaller.of(principals)
        val actor = caller.principal.actor
        val command =
            caller.commandOf {
                ChangeTenantScheduleCommand(
                    ActorId(actor),
                    CommercialRef(tenantRef),
                    body.scheduleRef,
                    body.goLiveAt?.toInstant(),
                    body.offboardingAt?.toInstant(),
                    RequestContext(caller.correlationId, idempotencyKey.toString()),
                )
            }
        val outcome = platform.bindPlatform(actor) { runner.run(change, actor, command) }
        return ResponseEntity.ok(responseOf(result(outcome, caller)))
    }

    fun cancel(
        tenantRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<TenantScheduleResponse> {
        val caller = OperatorCaller.of(principals)
        val actor = caller.principal.actor
        val command =
            caller.commandOf {
                CancelTenantScheduleCommand(
                    ActorId(actor),
                    CommercialRef(tenantRef),
                    RequestContext(caller.correlationId, idempotencyKey.toString()),
                )
            }
        val outcome = platform.bindPlatform(actor) { runner.run(cancel, actor, command) }
        return ResponseEntity.ok(responseOf(result(outcome, caller)))
    }

    fun get(tenantRef: String): ResponseEntity<TenantScheduleResponse> {
        val caller = OperatorCaller.of(principals)
        val actor = caller.principal.actor
        if (!get.permits(actor)) {
            problem(ClosedErrorCode.ACCESS_DENIED, caller.correlationId)
        }
        val ref = caller.commandOf { CommercialRef(tenantRef) }
        val found = platform.bindPlatform(actor) { transactions.inTransaction { get.get(ref) } }
        return ResponseEntity.ok(responseOf(found ?: problem(ClosedErrorCode.NOT_FOUND, caller.correlationId)))
    }

    private fun <R> result(
        outcome: OperationOutcome<R>,
        caller: OperatorCaller,
    ): R =
        when (outcome) {
            is OperationOutcome.Performed -> outcome.result
            is OperationOutcome.Repeated -> outcome.result
            OperationOutcome.NotPermitted -> problem(ClosedErrorCode.ACCESS_DENIED, caller.correlationId)
            is OperationOutcome.PreconditionUnmet -> refuse(outcome.code, caller.correlationId)
        }

    private fun refuse(
        code: String,
        correlationId: String,
    ): Nothing =
        when (code) {
            ScheduleRefusal.NOT_FOUND -> {
                problem(ClosedErrorCode.NOT_FOUND, correlationId)
            }

            ScheduleRefusal.SELF_APPROVAL -> {
                problem(ClosedErrorCode.ACCESS_DENIED, correlationId)
            }

            ScheduleRefusal.NOT_AWAITING,
            ScheduleRefusal.NOT_APPROVED,
            ScheduleRefusal.CARRIED_OUT,
            ScheduleRefusal.CHANGE_NOT_VALID_NOW,
            ScheduleRefusal.PENDING_EXISTS,
            ScheduleRefusal.SCHEDULE_EXISTS,
            -> {
                throw ProblemException(ScheduleProblems.conflict(code, correlationId))
            }

            ScheduleRule.MISSING_CONTRACT,
            ScheduleRule.OFFBOARDING_NOT_AFTER_GO_LIVE,
            ScheduleRule.GO_LIVE_NOT_AFTER_NOW,
            ScheduleRefusal.NO_TIME,
            -> {
                throw ProblemException(ScheduleProblems.ruleBroken(code, correlationId))
            }

            else -> {
                error("an undeclared refusal: $code")
            }
        }

    private fun responseOf(s: TenantSchedule) =
        TenantScheduleResponse(
            s.tenantRef.value,
            s.scheduleRef,
            TenantScheduleResponse.State.valueOf(s.state.name),
            s.contractReference.value,
            s.goLiveAt.atOffset(ZoneOffset.UTC),
            s.offboardingAt.atOffset(ZoneOffset.UTC),
            s.setBy,
            s.approvedBy,
        )
}
