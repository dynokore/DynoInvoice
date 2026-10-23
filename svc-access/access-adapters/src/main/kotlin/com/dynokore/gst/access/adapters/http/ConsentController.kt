package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.consent.GrantConsentCommand
import com.dynokore.gst.access.application.consent.GrantConsentOutcome
import com.dynokore.gst.access.application.consent.GrantConsentUseCase
import com.dynokore.gst.access.application.consent.WithdrawConsentOutcome
import com.dynokore.gst.access.application.consent.WithdrawConsentUseCase
import com.dynokore.gst.access.contract.api.ConsentApi
import com.dynokore.gst.access.contract.model.ConsentResponse
import com.dynokore.gst.access.contract.model.GrantConsentRequest
import com.dynokore.gst.access.contract.model.WithdrawConsentRequest
import com.dynokore.gst.access.domain.consent.Consent
import com.dynokore.gst.access.domain.consent.ConsentRef
import com.dynokore.gst.access.domain.consent.ConsentStatus
import com.dynokore.gst.access.domain.consent.ConsentTerms
import com.dynokore.gst.access.domain.consent.ScopeItem
import com.dynokore.gst.access.domain.grant.EntityRef
import com.dynokore.gst.access.domain.grant.IdentityRef
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * TPL-08. grantConsent and withdrawConsent (INV-REQ-713, INV-REQ-912, INV-REQ-955). The caller is the client signatory
 * of the client entity; the use cases check that with the live grants (`consent.grant`, `consent.withdraw`). A legacy
 * free-text `scope` is refused with 400 before anything is read or recorded (INV-REQ-912). acknowledgeMapping belongs
 * to another task and keeps answering 501.
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin in the
// template)
open class ConsentController(
    private val grantConsent: GrantConsentUseCase,
    private val withdrawConsent: WithdrawConsentUseCase,
    private val tenants: TenantContext,
) : ConsentApi {
    override fun grantConsent(
        idempotencyKey: UUID,
        grantConsentRequest: GrantConsentRequest,
    ): ResponseEntity<ConsentResponse> {
        if (!grantConsentRequest.scope.isNullOrEmpty()) {
            GrantRequests.invalid("scope is not supported; use submitInvoiceData, fileGstReturn and mappingApproval")
        }
        val command =
            GrantConsentCommand(
                tenant = TenantRef(tenants.current().toString()),
                actor = caller(),
                client = referenceOf("clientRef") { EntityRef(grantConsentRequest.clientRef) },
                agent = referenceOf("agentRef") { IdentityRef(grantConsentRequest.agentRef) },
                terms =
                    ConsentTerms(
                        items = itemsOf(grantConsentRequest),
                        validFrom = grantConsentRequest.validFrom,
                        authorisingPersonEmail = grantConsentRequest.authorisingPersonEmail,
                    ),
            )
        return when (val outcome = grantConsent.handle(command)) {
            is GrantConsentOutcome.Granted -> {
                ResponseEntity
                    .status(if (outcome.repeated) HttpStatus.OK else HttpStatus.CREATED)
                    .body(responseOf(outcome.consent))
            }

            else -> {
                throw refusalOf(outcome)
            }
        }
    }

    // the contract lists 403 and no 409 for this operation, and no catalogue error code names these refusals
    private fun refusalOf(outcome: GrantConsentOutcome): ApiRefusal =
        when (outcome) {
            GrantConsentOutcome.TenancyOff -> {
                ApiRefusal(ClosedErrorCode.ACCESS_DENIED, "agent tenancy is off in this installation")
            }

            is GrantConsentOutcome.OtherConsentLive -> {
                ApiRefusal(
                    ClosedErrorCode.ACCESS_DENIED,
                    "the client entity already has a live consent; withdraw it before recording another",
                )
            }

            else -> {
                ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
            }
        }

    override fun withdrawConsent(
        consentRef: String,
        idempotencyKey: UUID,
        withdrawConsentRequest: WithdrawConsentRequest,
    ): ResponseEntity<ConsentResponse> {
        val ref = runCatching { ConsentRef(consentRef) }.getOrNull()
        val outcome =
            if (ref == null) {
                WithdrawConsentOutcome.NotFound
            } else {
                withdrawConsent.handle(TenantRef(tenants.current().toString()), caller(), ref)
            }
        return when (outcome) {
            is WithdrawConsentOutcome.Withdrawn -> {
                ResponseEntity.ok(responseOf(outcome.consent))
            }

            WithdrawConsentOutcome.NotFound -> {
                throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
            }

            WithdrawConsentOutcome.NotSignatory -> {
                throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
            }
        }
    }

    /** The person is the authenticated subject of the call, never a request field. */
    private fun caller(): IdentityRef =
        GrantRequests.identityOf(checkNotNull(tenants.currentActor()) { "no actor is bound" })

    private fun <T> referenceOf(
        field: String,
        build: () -> T,
    ): T = runCatching(build).getOrElse { GrantRequests.invalid("$field is invalid") }

    private fun itemsOf(request: GrantConsentRequest): Set<ScopeItem> =
        buildSet {
            if (request.submitInvoiceData == true) add(ScopeItem.SUBMIT_INVOICE_DATA)
            if (request.fileGstReturn == true) add(ScopeItem.FILE_GST_RETURN)
            if (request.mappingApproval == true) add(ScopeItem.MAPPING_APPROVAL)
        }

    private fun responseOf(consent: Consent) =
        ConsentResponse(
            consentRef = consent.ref.value,
            clientRef = consent.client.value,
            agentRef = consent.agent.value,
            status =
                if (consent.status ==
                    ConsentStatus.LIVE
                ) {
                    ConsentResponse.Status.ACTIVE
                } else {
                    ConsentResponse.Status.WITHDRAWN
                },
            submitInvoiceData = ScopeItem.SUBMIT_INVOICE_DATA in consent.items,
            fileGstReturn = ScopeItem.FILE_GST_RETURN in consent.items,
            mappingApproval = ScopeItem.MAPPING_APPROVAL in consent.items,
            handoverStatementRef = consent.handoverStatementRef.takeIf { consent.status == ConsentStatus.WITHDRAWN },
        )
}
