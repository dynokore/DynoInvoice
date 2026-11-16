package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.catalogue.ErrorCode
import com.dynokore.gst.masterdata.adapters.permission.OperationPermissionKeys
import com.dynokore.gst.masterdata.application.clientsignatory.RecordClientSignatory
import com.dynokore.gst.masterdata.application.clientsignatory.RecordClientSignatoryCommand
import com.dynokore.gst.masterdata.application.permission.TenantPermission
import com.dynokore.gst.masterdata.application.request.CounterpartyDetails
import com.dynokore.gst.masterdata.application.request.SendCommand
import com.dynokore.gst.masterdata.application.request.SendCounterpartyRequest
import com.dynokore.gst.masterdata.application.request.SendOutcome
import com.dynokore.gst.masterdata.application.request.SubmitCounterpartyDetails
import com.dynokore.gst.masterdata.application.request.SubmitOutcome
import com.dynokore.gst.masterdata.contract.api.CounterpartyRequestsApi
import com.dynokore.gst.masterdata.contract.model.ClientSignatoryRecord
import com.dynokore.gst.masterdata.contract.model.Counterparty
import com.dynokore.gst.masterdata.contract.model.CounterpartyRequest
import com.dynokore.gst.masterdata.contract.model.RecordClientSignatoryRequest
import com.dynokore.gst.masterdata.contract.model.SendCounterpartyRequestBody
import com.dynokore.gst.masterdata.contract.model.SubmitCounterpartyDetailsRequest
import com.dynokore.gst.masterdata.domain.counterparty.PartyRef
import com.dynokore.gst.masterdata.domain.counterparty.PostalAddress
import com.dynokore.gst.masterdata.domain.request.LinkPrincipal
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.TokenIssuerUnavailableException
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.ZoneOffset
import java.util.UUID

/**
 * The counterparty request operations (INV-T-18221, INV-REQ-901, INV-REQ-902).
 *
 * `submitCounterpartyDetails` is marked `x-caller-auth: signed-link`, so the runtime's end-user filter leaves it alone
 * and the bearer credential is verified here by the shared verifier, the service's [EndUserPrincipalResolver] (a
 * link-session token: `typ` `link-session+jwt`, `sub` `link:<linkRef>`, verified against the link-session keys
 * only). Nothing is read or written before the verification: a missing, refused or tenantless token is one 401 SRT-003
 * and so is every refusal of the link evidence by the use case, which never says which check failed.
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller
@Suppress("LongParameterList")
open class CounterpartyRequestsController(
    private val submit: SubmitCounterpartyDetails,
    private val principals: ObjectProvider<EndUserPrincipalResolver>,
    private val tenants: TenantContext,
    private val recordClientSignatory: RecordClientSignatory,
    private val send: SendCounterpartyRequest,
    private val permission: TenantPermission,
    private val sendAudit: CounterpartyRequestSendAudit,
    private val tx: TransactionRunner,
) : CounterpartyRequestsApi {
    /**
     * `sendCounterpartyRequest` (INV-T-20197, INV-REQ-901, INV-REQ-1291): the entity outside the principal's set is
     * refused 403 SRT-004 by the runtime's gate before this method. The permission `counterparty-request.send` is then
     * decided per request through the replica (INV-Q-10027; a caller without it gets 403, nothing written), the entity
     * must be present and not blank (400 SRT-001), and the use case stores the request, writes SignedLinkRequested and
     * the audit entry in one transaction. A repeat while the link is live answers the open request and writes nothing.
     */
    override fun sendCounterpartyRequest(
        counterpartyRef: String,
        idempotencyKey: UUID,
        sendCounterpartyRequestBody: SendCounterpartyRequestBody,
    ): ResponseEntity<CounterpartyRequest> {
        val caller = permitted()
        val entity = entityOf(sendCounterpartyRequestBody)
        val party = partyOf(counterpartyRef)
        val command =
            SendCommand(
                entity,
                party,
                sendCounterpartyRequestBody.contactEmail,
                sendCounterpartyRequestBody.contactMobile,
            )
        val outcome =
            tx.inTransaction {
                val sent = send.handle(command)
                if (sent is SendOutcome.Sent && sent.emitted) sendAudit.sent(caller, sent.request.requestId, entity)
                sent
            }
        return when (outcome) {
            is SendOutcome.Sent -> {
                ResponseEntity.status(HttpStatus.CREATED).body(wire(outcome))
            }

            SendOutcome.InvalidContact -> {
                CounterpartyChangeMapping.invalid("contactEmail must not be blank")
            }

            SendOutcome.MethodNotLink -> {
                throw CatalogueRefusal(ErrorCode.MDM_011, HttpStatus.CONFLICT.value())
            }

            // fail closed: an option or validity configuration this service does not know is never guessed
            SendOutcome.UnknownMethod, SendOutcome.InvalidValidity -> {
                throw ApiRefusal(ClosedErrorCode.SERVICE_NOT_READY)
            }
        }
    }

    /** The acting user ID, once `counterparty-request.send` is decided for it through the replica (INV-Q-10027). */
    private fun permitted(): String {
        val caller = tenants.currentActor()?.takeIf { it.isNotBlank() }
        if (caller == null || !permission.holds(caller, OperationPermissionKeys.COUNTERPARTY_REQUEST_SEND)) {
            throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        }
        return caller
    }

    private fun entityOf(body: SendCounterpartyRequestBody): String =
        body.entityRef?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "entityRef is required")

    private fun partyOf(counterpartyRef: String): PartyRef =
        runCatching { PartyRef(counterpartyRef) }.getOrNull()
            ?: throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "counterpartyRef is invalid")

    private fun wire(sent: SendOutcome.Sent): CounterpartyRequest =
        CounterpartyRequest(
            requestId = sent.request.requestId,
            counterpartyRef = sent.request.party.value,
            status = CounterpartyRequest.Status.valueOf(sent.request.status.name),
            contactEmail = sent.request.contactEmail,
            contactMobile = sent.request.contactMobile,
            expiresAt = sent.request.expiresAt.atOffset(ZoneOffset.UTC),
        )

    /**
     * `recordClientSignatory` (INV-T-17443, INV-REQ-899): the use case decides `client-signatory.record` first and a
     * denied caller gets 403 SRT-004 with nothing stored; the caller is the actor bound to the request.
     */
    override fun recordClientSignatory(
        idempotencyKey: UUID,
        recordClientSignatoryRequest: RecordClientSignatoryRequest,
    ): ResponseEntity<ClientSignatoryRecord> {
        val caller =
            tenants.currentActor()?.takeIf { it.isNotBlank() } ?: throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        val outcome =
            recordClientSignatory.handle(
                RecordClientSignatoryCommand(
                    caller = caller,
                    clientRef = recordClientSignatoryRequest.clientRef,
                    signatoryName = recordClientSignatoryRequest.signatoryName,
                    signatoryEmail = recordClientSignatoryRequest.signatoryEmail,
                    signatoryMobile = recordClientSignatoryRequest.signatoryMobile,
                    consentRef = recordClientSignatoryRequest.consentRef,
                ),
            )
        return ClientSignatoryMapping.answer(outcome)
    }

    override fun submitCounterpartyDetails(
        requestId: String,
        idempotencyKey: UUID,
        submitCounterpartyDetailsRequest: SubmitCounterpartyDetailsRequest,
    ): ResponseEntity<Counterparty> {
        val principal = verified()
        val tenant = principal.tenant ?: throw unauthenticated()
        val details = details(submitCounterpartyDetailsRequest)
        val outcome =
            tenants.bind(tenant, principal.actor) {
                submit.handle(LinkPrincipal(principal.actor, tenant.toString()), requestId, details)
            }
        return when (outcome) {
            is SubmitOutcome.Accepted -> {
                ResponseEntity.ok(CounterpartyMapping.counterparty(outcome.version, emptyList()))
            }

            SubmitOutcome.Refused -> {
                throw unauthenticated()
            }

            SubmitOutcome.Incomplete -> {
                CounterpartyChangeMapping.invalid("uen, legalName and address must not be blank")
            }
        }
    }

    private fun details(request: SubmitCounterpartyDetailsRequest): CounterpartyDetails {
        val detail = request.addressDetail
        return CounterpartyDetails(
            uen = request.uen,
            legalName = request.legalName,
            address =
                PostalAddress(
                    line1 = detail?.addressLine1 ?: request.address,
                    line2 = detail?.addressLine2,
                    postalCode = detail?.postalCode,
                ),
        )
    }

    /** The subject and tenant of a verified link-session token; nothing else of the principal is read. */
    private class Verified(
        val actor: String,
        val tenant: TenantId?,
    )

    private fun verified(): Verified =
        try {
            // a service with no identity provider bound has no resolver: every call is unauthenticated, never open
            principals.getIfAvailable()?.resolve(authorization())?.let { Verified(it.actor, it.tenant) }
        } catch (_: TokenIssuerUnavailableException) {
            throw ApiRefusal(ClosedErrorCode.ISSUER_UNAVAILABLE)
        } ?: throw unauthenticated()

    private fun authorization(): String? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.getHeader("Authorization")

    private fun unauthenticated() = ApiRefusal(ClosedErrorCode.UNAUTHENTICATED)
}
