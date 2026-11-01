package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.invitation.ProvisionFirstAdministrator
import com.dynokore.gst.access.application.invitation.ProvisionFirstAdministratorCommand
import com.dynokore.gst.access.application.invitation.ProvisionFirstAdministratorOutcome
import com.dynokore.gst.access.contract.model.FirstAdministratorRequest
import com.dynokore.gst.access.contract.model.FirstAdministratorResponse
import com.dynokore.gst.access.domain.grant.TenantRef
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.serviceruntime.identity.ServiceCaller
import com.dynokore.platform.serviceruntime.identity.WorkloadPrincipal
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.util.UUID

/**
 * The inbound adapter of `provisionFirstAdministrator` and `provisionInstallationFirstAdministrator` (INV-Q-14267 B):
 * the body `tenantRef` is parsed as a tenant id and bound around the whole call with the `sub` of the verified end-user
 * token as the actor (a call with no end-user principal: `svc:` + the verified calling workload). A token that carries another tenant is refused (403), a token with no tenant (a platform
 * operator) is bound to `tenantRef`. The caller check itself is generated from the contract by service-runtime.
 */
class FirstAdministratorProvisioning(
    private val useCase: ProvisionFirstAdministrator,
    private val tenants: TenantContext,
    private val request: HttpServletRequest,
    private val serviceCaller: ServiceCaller,
) {
    fun provision(
        idempotencyKey: UUID,
        body: FirstAdministratorRequest,
    ): ResponseEntity<FirstAdministratorResponse> {
        val tenant = tenantOf(body.tenantRef)
        val principal = request.getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal
        val tokenTenant = principal?.tenant
        if (tokenTenant != null && tokenTenant != tenant) refuse(ClosedErrorCode.ACCESS_DENIED)
        val actor = principal?.actor ?: WorkloadPrincipal(verifiedCaller()).value
        val command =
            ProvisionFirstAdministratorCommand(
                tenant = TenantRef(tenant.toString()),
                actor = actor,
                idempotencyKey = idempotencyKey,
                administratorEmail = body.administratorEmail,
                invitationExpiresAt = body.invitationExpiresAt.toInstant(),
            )
        return when (val outcome = tenants.bind(tenant, actor) { useCase.handle(command) }) {
            is ProvisionFirstAdministratorOutcome.Provisioned -> {
                ResponseEntity.status(HttpStatus.CREATED).body(
                    FirstAdministratorResponse(
                        grantRef = outcome.grantRef,
                        status = FirstAdministratorResponse.Status.PENDING,
                        invitationRef = outcome.invitationRef,
                        invitationCode = outcome.invitationCode,
                    ),
                )
            }

            ProvisionFirstAdministratorOutcome.TenantHasAdministrator -> {
                conflict("the tenant already has a tenant.admin grant")
            }

            ProvisionFirstAdministratorOutcome.DifferentRequest -> {
                refuse(ClosedErrorCode.DIFFERENT_REQUEST)
            }

            ProvisionFirstAdministratorOutcome.CodeKeyUnavailable -> {
                refuse(ClosedErrorCode.ISSUER_UNAVAILABLE)
            }
        }
    }

    /** The verified calling workload; a null reading is refused before the use case runs (defence in depth). */
    private fun verifiedCaller(): String = serviceCaller.current() ?: throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)

    private fun refuse(code: ClosedErrorCode): Nothing = throw ApiRefusal(code)

    private fun conflict(detail: String): Nothing = throw ApiConflict(detail)

    /** The exact lower-case text of a customer tenant id; any other text, the platform tenant included, is 400. */
    private fun tenantOf(text: String): TenantId {
        val tenant = runCatching { TenantId.of(text) }.getOrNull()
        if (tenant == null || tenant.isPlatform || tenant.toString() != text) {
            GrantRequests.invalid("tenantRef is not a tenant id")
        }
        return tenant
    }
}
