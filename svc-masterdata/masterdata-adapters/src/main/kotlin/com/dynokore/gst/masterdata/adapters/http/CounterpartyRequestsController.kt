package com.dynokore.gst.masterdata.adapters.http

import com.dynokore.gst.masterdata.application.request.CounterpartyDetails
import com.dynokore.gst.masterdata.application.request.SubmitCounterpartyDetails
import com.dynokore.gst.masterdata.application.request.SubmitOutcome
import com.dynokore.gst.masterdata.contract.api.CounterpartyRequestsApi
import com.dynokore.gst.masterdata.contract.model.Counterparty
import com.dynokore.gst.masterdata.contract.model.SubmitCounterpartyDetailsRequest
import com.dynokore.gst.masterdata.domain.counterparty.PostalAddress
import com.dynokore.gst.masterdata.domain.request.LinkPrincipal
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.identity.TokenIssuerUnavailableException
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
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
open class CounterpartyRequestsController(
    private val submit: SubmitCounterpartyDetails,
    private val principals: ObjectProvider<EndUserPrincipalResolver>,
    private val tenants: TenantContext,
) : CounterpartyRequestsApi {
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
