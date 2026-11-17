package com.dynokore.gst.access.adapters.consultant

import com.dynokore.gst.access.adapters.elevation.CommandKey
import com.dynokore.gst.access.adapters.elevation.ElevationCommands
import com.dynokore.gst.access.adapters.elevation.ElevationCommands.Reply
import com.dynokore.gst.access.adapters.http.ApiConflict
import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.application.consultant.ConsultantDecisionOutcome
import com.dynokore.gst.access.application.consultant.ConsultantGrantKeys
import com.dynokore.gst.access.application.consultant.DecideConsultantGrantUseCase
import com.dynokore.gst.access.application.consultant.RequestConsultantGrantUseCase
import com.dynokore.gst.access.application.elevation.ElevationCallers
import com.dynokore.gst.access.contract.model.ConsultantGrantDecisionRequest
import com.dynokore.gst.access.contract.model.ConsultantGrantRequest
import com.dynokore.gst.access.contract.model.ConsultantGrantRequestResponse
import com.dynokore.gst.access.domain.consultant.ConsultantGrantAsk
import com.dynokore.gst.access.domain.consultant.ConsultantGrantRecord
import com.dynokore.gst.access.domain.consultant.ConsultantRequestStatus
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.util.UUID

/**
 * TPL-08. `requestConsultantGrant` and `decideConsultantGrant` (INV-REQ-908, INV-REQ-2465), served by the grants
 * controller because the contract tags them `grants`. Each asks the live grants for one permission key and never
 * compares a role name (INV-Q-10027): the request needs `consultant-grant.request` on the operator's platform grants,
 * the decision needs `user.manage` on the caller's grants of the bound tenant. A request is for the bound tenant only.
 */
class ConsultantGrantEndpoints(
    private val request: RequestConsultantGrantUseCase,
    private val decide: DecideConsultantGrantUseCase,
    private val callers: ElevationCallers,
    private val tenants: TenantContext,
    private val commands: ElevationCommands,
) {
    fun request(
        idempotencyKey: UUID,
        body: ConsultantGrantRequest,
    ): ResponseEntity<ConsultantGrantRequestResponse> {
        val actor = actor()
        val tenant = boundTenantOf(actor, body)
        val identity = identityOf(body)
        val ask = ConsultantGrantAsk(TenantRef(tenant), identity, body.goLiveDate, actor)
        val id = CommandKey(actor, idempotencyKey, "POST requestConsultantGrant")
        return commands.once(id, body, commands.reader(ConsultantGrantRequestResponse::class.java)) {
            Reply(HttpStatus.CREATED, bodyOf(request.handle(ask)))
        }
    }

    fun decide(
        requestRef: String,
        idempotencyKey: UUID,
        body: ConsultantGrantDecisionRequest,
    ): ResponseEntity<ConsultantGrantRequestResponse> {
        val actor = actor()
        if (!callers.tenantHolds(actor, TenantRef(tenants.current().toString()), ConsultantGrantKeys.APPROVE)) {
            throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        }
        val approve = body.decision == ConsultantGrantDecisionRequest.Decision.APPROVE
        val id = CommandKey(actor, idempotencyKey, "POST decideConsultantGrant $requestRef")
        return commands.once(id, body, commands.reader(ConsultantGrantRequestResponse::class.java)) {
            Reply(HttpStatus.OK, bodyOf(settled(decide.handle(actor, requestRef, approve, body.periodDays))))
        }
    }

    /** The bound tenant, after the live check of the request key and of the tenant of the call; 403 otherwise. */
    private fun boundTenantOf(
        actor: IdentityRef,
        body: ConsultantGrantRequest,
    ): String {
        val tenant = tenants.current().toString()
        if (!callers.operatorHolds(actor, ConsultantGrantKeys.REQUEST) || body.tenantRef != tenant) {
            throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        }
        return tenant
    }

    private fun identityOf(body: ConsultantGrantRequest) =
        runCatching { IdentityRef(body.identityRef) }.getOrElse {
            throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "identityRef is not valid")
        }

    private fun actor() = IdentityRef(checkNotNull(tenants.currentActor()) { "no actor is bound" })

    private fun settled(outcome: ConsultantDecisionOutcome): ConsultantGrantRecord =
        when (outcome) {
            is ConsultantDecisionOutcome.Approved -> {
                outcome.record
            }

            is ConsultantDecisionOutcome.Denied -> {
                outcome.record
            }

            ConsultantDecisionOutcome.NotFound -> {
                throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
            }

            ConsultantDecisionOutcome.NotPending -> {
                throw ApiConflict("the request is not pending")
            }

            ConsultantDecisionOutcome.SameActor -> {
                throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED, "the approver must differ from the requester")
            }

            ConsultantDecisionOutcome.PeriodOutOfRange -> {
                throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "periodDays must be 1 to 120")
            }

            ConsultantDecisionOutcome.PeriodNotAvailable -> {
                throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "periodDays other than 30 is not available yet")
            }

            ConsultantDecisionOutcome.EndsNotAfterApproval -> {
                throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "the grant would not end after today")
            }

            is ConsultantDecisionOutcome.GrantRefused -> {
                throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED, "the grant was refused: ${outcome.outcome}")
            }
        }

    private fun bodyOf(record: ConsultantGrantRecord) =
        ConsultantGrantRequestResponse(
            requestRef = record.ref,
            tenantRef = record.ask.tenant.value,
            identityRef = record.ask.identity.value,
            goLiveDate = record.ask.goLiveDate,
            status =
                when (record.status) {
                    ConsultantRequestStatus.PENDING -> ConsultantGrantRequestResponse.Status.PENDING
                    ConsultantRequestStatus.APPROVED -> ConsultantGrantRequestResponse.Status.APPROVED
                    ConsultantRequestStatus.DENIED -> ConsultantGrantRequestResponse.Status.DENIED
                },
            grantRef = record.grant?.value,
            validTo = record.validTo,
        )
}
