package com.dynokore.gst.transport.adapters.http

import com.dynokore.gst.transport.application.DocUuidRecordedUnderOtherEntity
import com.dynokore.gst.transport.application.SubmitPayload
import com.dynokore.gst.transport.contract.api.DespatchApi
import com.dynokore.gst.transport.contract.model.Acknowledgement
import com.dynokore.gst.transport.contract.model.SubmitPayloadRequest
import com.dynokore.gst.transport.contract.model.TransportOutcomeReasonsInner
import com.dynokore.gst.transport.domain.DocRef
import com.dynokore.gst.transport.domain.EntityRef
import com.dynokore.gst.transport.domain.TransportOutcome
import com.dynokore.platform.outboxandqueue.TenantScope
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.TokenIssuerUnavailableException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import com.dynokore.gst.transport.contract.model.TransportOutcome as TransportOutcomeBody

/**
 * The `despatch` operations of the transport contract (TPL-08): submitPayload, submitPackage and sendNetworkDocument.
 * `submitPayload` is implemented here (INV-T-4981); the other two answer 501 until the task named for each in
 * `operations.tsv` overrides its method: the generated interface's default is NOT_IMPLEMENTED.
 *
 * submitPayload (INV-Q-8339 D, INV-Q-8387; API-24, API-25): a machine-to-machine call, so no authorisation decision
 * is asked and no entity set is read. The calling service is checked before this method runs, by the runtime's
 * `SyncEdgeCallerFilter` from the `x-sync-edge` of the contract (403 SRT-004, nothing stored); the workload credential
 * by `WorkloadTokenAuthenticationFilter` (401). Here only the tenant of the token is required: the request reads and
 * writes under it alone. A Doc UUID already recorded under another entity is 404 SRT-005.
 */
@RestController
open class DespatchController(
    private val submit: SubmitPayload,
    private val principals: ObjectProvider<EndUserPrincipalResolver>,
    private val scope: TenantScope,
    private val token: CallerToken,
) : DespatchApi {
    override fun submitPayload(
        idempotencyKey: UUID,
        submitPayloadRequest: SubmitPayloadRequest,
    ): ResponseEntity<TransportOutcomeBody> {
        val principal = caller()
        val entity = EntityRef(submitPayloadRequest.entityRef)
        val tenant = principal.tenant ?: throw denied()
        val doc = DocRef(submitPayloadRequest.docUuid, submitPayloadRequest.version)
        val payload = String(submitPayloadRequest.payload, StandardCharsets.UTF_8)
        val (outcome, receivedAt) =
            scope.bind(tenant, principal.actor) {
                val result =
                    try {
                        submit.submit(entity, doc, payload)
                    } catch (_: DocUuidRecordedUnderOtherEntity) {
                        throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
                    }
                result to (result as? TransportOutcome.Accepted)?.let { submit.receivedAt(doc) }
            }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body(doc, outcome, receivedAt))
    }

    private fun caller(): EndUserPrincipal =
        try {
            // a service with no identity provider bound has no resolver: every call is unauthenticated, never open
            principals.getIfAvailable()?.resolve(token.authorization())
        } catch (_: TokenIssuerUnavailableException) {
            throw ApiRefusal(ClosedErrorCode.ISSUER_UNAVAILABLE)
        } ?: throw ApiRefusal(ClosedErrorCode.UNAUTHENTICATED)

    private fun denied() = ApiRefusal(ClosedErrorCode.ACCESS_DENIED)

    private fun body(
        doc: DocRef,
        outcome: TransportOutcome,
        receivedAt: Instant?,
    ): TransportOutcomeBody =
        when (outcome) {
            is TransportOutcome.Accepted -> {
                TransportOutcomeBody(
                    docUuid = doc.docUuid,
                    version = doc.version,
                    outcome = TransportOutcomeBody.Outcome.ACCEPTED,
                    acknowledgement =
                        Acknowledgement(
                            reference = outcome.acknowledgementId,
                            receivedAt = checkNotNull(receivedAt).atOffset(ZoneOffset.UTC),
                        ),
                )
            }

            is TransportOutcome.Rejected -> {
                TransportOutcomeBody(
                    docUuid = doc.docUuid,
                    version = doc.version,
                    outcome = TransportOutcomeBody.Outcome.REJECTED,
                    reasons = outcome.reasons.map { TransportOutcomeReasonsInner(it.code, it.message, it.field) },
                )
            }

            TransportOutcome.TransientFailure -> {
                TransportOutcomeBody(
                    docUuid = doc.docUuid,
                    version = doc.version,
                    outcome = TransportOutcomeBody.Outcome.TRANSIENT_FAILURE,
                )
            }
        }
}
