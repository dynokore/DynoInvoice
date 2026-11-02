package com.dynokore.gst.access.adapters.elevation

import com.dynokore.gst.access.adapters.http.ApiConflict
import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.application.elevation.DecideElevationUseCase
import com.dynokore.gst.access.application.elevation.ElevationCallers
import com.dynokore.gst.access.application.elevation.ElevationKeys
import com.dynokore.gst.access.application.elevation.ElevationOutcome
import com.dynokore.gst.access.application.elevation.OpenElevationUseCase
import com.dynokore.gst.access.application.elevation.WithdrawElevationUseCase
import com.dynokore.gst.access.contract.api.ElevationApi
import com.dynokore.gst.access.contract.model.DecideElevation200Response
import com.dynokore.gst.access.contract.model.DecideElevationRequest
import com.dynokore.gst.access.contract.model.DiagnosticInvitationRequest
import com.dynokore.gst.access.contract.model.DiagnosticInvitationResponse
import com.dynokore.gst.access.contract.model.ElevationRequest
import com.dynokore.gst.access.contract.model.ElevationResponse
import com.dynokore.gst.access.domain.elevation.ElevationAsk
import com.dynokore.gst.access.domain.elevation.ElevationKind
import com.dynokore.gst.access.domain.elevation.ElevationRecord
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * TPL-08. The five operations of INV-T-4941 (INV-REQ-862, INV-REQ-888, INV-REQ-890). Each asks the live grants for one
 * permission key and never compares a role name (INV-Q-15950 A): `requestElevation` and `withdrawElevationRequest` for
 * `elevation.request`, `inviteDiagnostics` and `withdrawDiagnosticInvitation` for `diagnostic.invite`, both read from
 * the operator's platform grants; `decideElevation` for `elevation.decide`, read from the caller's grants of the bound
 * tenant. A record is read and written in the bound tenant only, so another tenant's reference is 404. `openOperatorSession`
 * is INV-T-4154 and stays unimplemented here.
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin in the
// template)
open class ElevationController(
    private val open: OpenElevationUseCase,
    private val decide: DecideElevationUseCase,
    private val withdraw: WithdrawElevationUseCase,
    private val callers: ElevationCallers,
    private val tenants: TenantContext,
) : ElevationApi {
    override fun requestElevation(
        idempotencyKey: UUID,
        elevationRequest: ElevationRequest,
    ): ResponseEntity<ElevationResponse> {
        val actor = operator(ElevationKeys.REQUEST)
        val tenant = tenants.current().toString()
        // a request is for the bound tenant only
        if (elevationRequest.tenantRef != tenant) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        val ask = ElevationAsk(ElevationKind.ELEVATION, tenant, actor, elevationRequest.caseId)
        val record = opened(open.handle(actor, ask, elevationRequest.reason))
        return ResponseEntity.status(HttpStatus.CREATED).body(elevationBody(record))
    }

    override fun inviteDiagnostics(
        idempotencyKey: UUID,
        diagnosticInvitationRequest: DiagnosticInvitationRequest,
    ): ResponseEntity<DiagnosticInvitationResponse> {
        val actor = operator(ElevationKeys.INVITE)
        val ask =
            ElevationAsk(
                ElevationKind.DIAGNOSTIC_INVITATION,
                diagnosticInvitationRequest.installationRef,
                actor,
                diagnosticInvitationRequest.caseId,
            )
        val record = opened(open.handle(actor, ask, null))
        return ResponseEntity.status(HttpStatus.CREATED).body(invitationBody(record))
    }

    override fun decideElevation(
        requestRef: String,
        idempotencyKey: UUID,
        decideElevationRequest: DecideElevationRequest,
    ): ResponseEntity<DecideElevation200Response> {
        val actor = IdentityRef(checkNotNull(tenants.currentActor()) { "no actor is bound" })
        if (!callers.tenantHolds(actor, TenantRef(tenants.current().toString()), ElevationKeys.DECIDE)) {
            throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        }
        val grant = decideElevationRequest.decision == DecideElevationRequest.Decision.GRANT
        val record = settled(decide.handle(actor, requestRef, grant, decideElevationRequest.validForMinutes))
        val body: DecideElevation200Response =
            if (record.kind == ElevationKind.ELEVATION) elevationBody(record) else invitationBody(record)
        return ResponseEntity.ok(body)
    }

    override fun withdrawElevationRequest(
        requestRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<Unit> {
        val actor = operator(ElevationKeys.REQUEST)
        settled(withdraw.handle(actor, requestRef, ElevationKind.ELEVATION))
        return ResponseEntity.noContent().build()
    }

    override fun withdrawDiagnosticInvitation(
        invitationRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<Unit> {
        val actor = operator(ElevationKeys.INVITE)
        settled(withdraw.handle(actor, invitationRef, ElevationKind.DIAGNOSTIC_INVITATION))
        return ResponseEntity.noContent().build()
    }

    /** The calling operator, after the live check of [permission] on its platform grants; 403 otherwise. */
    private fun operator(permission: Permission): IdentityRef {
        val actor = IdentityRef(checkNotNull(tenants.currentActor()) { "no actor is bound" })
        if (!callers.operatorHolds(actor, permission)) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        return actor
    }

    private fun opened(outcome: ElevationOutcome): ElevationRecord =
        when (outcome) {
            is ElevationOutcome.Done -> outcome.record
            ElevationOutcome.BadCase -> throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "caseId is not valid")
            else -> error("an opening never ends as $outcome")
        }

    private fun settled(outcome: ElevationOutcome): ElevationRecord =
        when (outcome) {
            is ElevationOutcome.Done -> {
                outcome.record
            }

            ElevationOutcome.NotFound -> {
                throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
            }

            ElevationOutcome.NotPending -> {
                throw ApiConflict("the request is no longer pending")
            }

            ElevationOutcome.BadDecision -> {
                throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "validForMinutes is required to grant")
            }

            ElevationOutcome.BadCase -> {
                error("a decision never ends as $outcome")
            }
        }

    private fun elevationBody(record: ElevationRecord) =
        ElevationResponse(record.ref, record.caseId, ElevationResponse.Status.valueOf(record.status.name))

    private fun invitationBody(record: ElevationRecord) =
        DiagnosticInvitationResponse(
            record.ref,
            record.subject,
            DiagnosticInvitationResponse.Status.valueOf(record.status.name),
        )
}
