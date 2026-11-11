package com.dynokore.gst.access.adapters.elevation

import com.dynokore.gst.access.adapters.elevation.ElevationCommands.Reply
import com.dynokore.gst.access.adapters.http.ApiConflict
import com.dynokore.gst.access.adapters.http.ApiRefusal
import com.dynokore.gst.access.application.elevation.DecideElevationUseCase
import com.dynokore.gst.access.application.elevation.ElevationCallers
import com.dynokore.gst.access.application.elevation.ElevationKeys
import com.dynokore.gst.access.application.elevation.ElevationOutcome
import com.dynokore.gst.access.application.elevation.OpenElevationUseCase
import com.dynokore.gst.access.application.elevation.WithdrawElevationUseCase
import com.dynokore.gst.access.application.operatorsession.OpenOperatorSessionUseCase
import com.dynokore.gst.access.application.operatorsession.SessionPreparation
import com.dynokore.gst.access.contract.api.ElevationApi
import com.dynokore.gst.access.contract.model.DecideElevation200Response
import com.dynokore.gst.access.contract.model.DecideElevationRequest
import com.dynokore.gst.access.contract.model.DiagnosticInvitationRequest
import com.dynokore.gst.access.contract.model.DiagnosticInvitationResponse
import com.dynokore.gst.access.contract.model.ElevationRequest
import com.dynokore.gst.access.contract.model.ElevationResponse
import com.dynokore.gst.access.contract.model.OperatorSessionRequest
import com.dynokore.gst.access.contract.model.OperatorSessionResponse
import com.dynokore.gst.access.domain.elevation.ElevationAsk
import com.dynokore.gst.access.domain.elevation.ElevationKind
import com.dynokore.gst.access.domain.elevation.ElevationRecord
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.gst.access.domain.role.Permission
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.http.IdempotencyOutcome
import com.dynokore.platform.serviceruntime.http.IdempotencyStore
import com.dynokore.platform.serviceruntime.http.StoredResponse
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.ZoneOffset
import java.util.UUID

/**
 * TPL-08. The five operations of INV-T-4941 (INV-REQ-862, INV-REQ-888, INV-REQ-890). Each asks the live grants for one
 * permission key and never compares a role name (INV-Q-15950 A): `requestElevation` and `withdrawElevationRequest` for
 * `elevation.request`, `inviteDiagnostics` and `withdrawDiagnosticInvitation` for `diagnostic.invite`, both read from
 * the operator's platform grants; `decideElevation` for `elevation.decide`, read from the caller's grants of the bound
 * tenant. A record is read and written in the bound tenant only, so another tenant's reference is 404.
 * `openOperatorSession` (INV-T-4154) asks for `operator-session.open` on the operator's live platform grants.
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
    private val commands: ElevationCommands,
    private val opening: OpenOperatorSessionUseCase,
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
        val id = CommandKey(actor, idempotencyKey, "POST requestElevation")
        return commands.once(id, elevationRequest, commands.reader(ElevationResponse::class.java)) {
            Reply(HttpStatus.CREATED, elevationBody(opened(open.handle(actor, ask, elevationRequest.reason))))
        }
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
        return commands.once(
            CommandKey(actor, idempotencyKey, "POST inviteDiagnostics"),
            diagnosticInvitationRequest,
            commands.reader(DiagnosticInvitationResponse::class.java),
        ) {
            Reply(HttpStatus.CREATED, invitationBody(opened(open.handle(actor, ask, null))))
        }
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
        return commands.once(
            CommandKey(actor, idempotencyKey, "POST decideElevation $requestRef"),
            decideElevationRequest,
            commands::decisionBody,
        ) {
            val record = settled(decide.handle(actor, requestRef, grant, decideElevationRequest.validForMinutes))
            Reply(
                HttpStatus.OK,
                if (record.kind ==
                    ElevationKind.ELEVATION
                ) {
                    elevationBody(record)
                } else {
                    invitationBody(record)
                },
            )
        }
    }

    override fun withdrawElevationRequest(
        requestRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<Unit> {
        val actor = operator(ElevationKeys.REQUEST)
        val id = CommandKey(actor, idempotencyKey, "DELETE withdrawElevationRequest $requestRef")
        return commands.once(id, null, null) {
            settled(withdraw.handle(actor, requestRef, ElevationKind.ELEVATION))
            Reply(HttpStatus.NO_CONTENT, null)
        }
    }

    override fun withdrawDiagnosticInvitation(
        invitationRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<Unit> {
        val actor = operator(ElevationKeys.INVITE)
        val id = CommandKey(actor, idempotencyKey, "DELETE withdrawDiagnosticInvitation $invitationRef")
        return commands.once(id, null, null) {
            settled(withdraw.handle(actor, invitationRef, ElevationKind.DIAGNOSTIC_INVITATION))
            Reply(HttpStatus.NO_CONTENT, null)
        }
    }

    override fun openOperatorSession(
        idempotencyKey: UUID,
        operatorSessionRequest: OperatorSessionRequest,
    ): ResponseEntity<OperatorSessionResponse> {
        val actor = IdentityRef(checkNotNull(tenants.currentActor()) { "no actor is bound" })
        // the grant, the case, the tenant and the key publication are settled outside any transaction
        val ready = prepared(opening.prepare(actor, operatorSessionRequest.tenantRef, operatorSessionRequest.caseId))
        val id = CommandKey(actor, idempotencyKey, "POST openOperatorSession")
        return tenants.bind(ready.tenant, actor.value) {
            commands.once(id, operatorSessionRequest, commands.reader(OperatorSessionResponse::class.java)) {
                val opened = opening.open(ready)
                val mode =
                    if (opened.elevated) {
                        OperatorSessionResponse.ContentMode.ELEVATED
                    } else {
                        OperatorSessionResponse.ContentMode.MASKED
                    }
                Reply(
                    HttpStatus.CREATED,
                    OperatorSessionResponse(opened.token, opened.expiresAt.atOffset(ZoneOffset.UTC), mode),
                )
            }
        }
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

private fun prepared(outcome: SessionPreparation): SessionPreparation.Ready =
    when (outcome) {
        is SessionPreparation.Ready -> outcome
        SessionPreparation.Forbidden -> throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        SessionPreparation.BadCase -> throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "caseId is not valid")
        SessionPreparation.NoTenant -> throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
        SessionPreparation.Unavailable -> throw ApiRefusal(ClosedErrorCode.ISSUER_UNAVAILABLE)
    }
