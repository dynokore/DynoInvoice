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
import java.util.UUID

/**
 * TPL-08. The five operations of INV-T-4941 (INV-REQ-862, INV-REQ-888, INV-REQ-890). Each asks the live grants for one
 * permission key and never compares a role name (INV-Q-15950 A): `requestElevation` and `withdrawElevationRequest` for
 * `elevation.request`, `inviteDiagnostics` and `withdrawDiagnosticInvitation` for `diagnostic.invite`, both read from
 * the operator's platform grants; `decideElevation` for `elevation.decide`, read from the caller's grants of the bound
 * tenant. A record is read and written in the bound tenant only, so another tenant's reference is 404.
 * `openOperatorSession` is INV-T-4154 and stays unimplemented here.
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
    private val tx: TransactionRunner,
    private val idempotency: IdempotencyStore,
    private val mapper: ObjectMapper,
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
        return once(actor, idempotencyKey, "POST requestElevation", elevationRequest, {
            mapper.readValue(it, ElevationResponse::class.java)
        }) {
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
        return once(
            actor,
            idempotencyKey,
            "POST inviteDiagnostics",
            diagnosticInvitationRequest,
            { mapper.readValue(it, DiagnosticInvitationResponse::class.java) },
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
        return once(
            actor,
            idempotencyKey,
            "POST decideElevation $requestRef",
            decideElevationRequest,
            ::decisionBody,
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
        return once(actor, idempotencyKey, "DELETE withdrawElevationRequest $requestRef", null, null) {
            settled(withdraw.handle(actor, requestRef, ElevationKind.ELEVATION))
            Reply(HttpStatus.NO_CONTENT, null)
        }
    }

    override fun withdrawDiagnosticInvitation(
        invitationRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<Unit> {
        val actor = operator(ElevationKeys.INVITE)
        return once(actor, idempotencyKey, "DELETE withdrawDiagnosticInvitation $invitationRef", null, null) {
            settled(withdraw.handle(actor, invitationRef, ElevationKind.DIAGNOSTIC_INVITATION))
            Reply(HttpStatus.NO_CONTENT, null)
        }
    }

    /** What a command answers: the status and the body, or null for a 204. */
    private class Reply<T : Any>(
        val status: HttpStatus,
        val body: T?,
    )

    /**
     * API-15, TPL-04: the command and the key record commit in one transaction. A replay of the same key with the same
     * method, operation and body returns the stored status and body; the same key with another one is 422, one still
     * running is 409. A refusal thrown by [command] rolls back and stores nothing.
     */
    private fun <B : Any, T> once(
        actor: IdentityRef,
        key: UUID,
        operation: String,
        request: B?,
        read: ((String) -> T)?,
        command: () -> Reply<T>,
    ): ResponseEntity<T> where T : Any {
        val fingerprint = fingerprint(operation, request?.let { mapper.writeValueAsBytes(it) } ?: ByteArray(0))
        val outcome =
            tx.inTransaction {
                idempotency.once(tenants.current(), actor.value, key, fingerprint) {
                    val reply = command()
                    StoredResponse(reply.status.value(), reply.body?.let { mapper.writeValueAsString(it) }.orEmpty())
                }
            }
        val stored =
            when (outcome) {
                is IdempotencyOutcome.Fresh -> outcome.response
                is IdempotencyOutcome.Replay -> outcome.response
                IdempotencyOutcome.InFlight -> throw ApiRefusal(ClosedErrorCode.IN_FLIGHT)
                IdempotencyOutcome.DifferentRequest -> throw ApiRefusal(ClosedErrorCode.DIFFERENT_REQUEST)
            }
        val status = HttpStatus.valueOf(stored.status)
        return if (read == null || stored.body.isEmpty()) {
            ResponseEntity.status(status).build()
        } else {
            ResponseEntity.status(status).body(read(stored.body))
        }
    }

    private fun fingerprint(
        operation: String,
        body: ByteArray,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("$operation\n".toByteArray(StandardCharsets.UTF_8))
        digest.update(body)
        return digest.digest().joinToString("") { "%02x".format(it) }
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

    /** A decision answers the record it settled: an elevation (has a case) or a diagnostic invitation. */
    private fun decisionBody(stored: String): DecideElevation200Response =
        if (mapper.readTree(stored).has("caseId")) {
            mapper.readValue(stored, ElevationResponse::class.java)
        } else {
            mapper.readValue(stored, DiagnosticInvitationResponse::class.java)
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
