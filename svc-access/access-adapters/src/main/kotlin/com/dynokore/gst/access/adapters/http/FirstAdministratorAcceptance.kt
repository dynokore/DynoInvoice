package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.invitation.AcceptFirstAdministratorCommand
import com.dynokore.gst.access.application.invitation.AcceptFirstAdministratorInvitation
import com.dynokore.gst.access.application.invitation.AcceptFirstAdministratorOutcome
import com.dynokore.gst.access.application.invitation.InvitationTenantLookup
import com.dynokore.gst.access.contract.model.AcceptFirstAdministratorInvitationRequest
import com.dynokore.gst.access.contract.model.AcceptFirstAdministratorInvitationResponse
import com.dynokore.gst.access.domain.grant.Grant
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipal
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalBindingFilter
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TenantContext
import com.dynokore.platform.tenancykernel.TenantId
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity

/** The two inbound adapters of the first administrator, so that the controller keeps a short constructor. */
class FirstAdministratorEndpoints(
    val provisioning: FirstAdministratorProvisioning,
    val acceptance: FirstAdministratorAcceptance,
)

/**
 * The inbound adapter of `acceptFirstAdministratorInvitation` (INV-FLOW-227 step 3). The token carries no tenant for
 * the invited person, so the tenant of the invitation is found from its reference under the platform binding
 * ([InvitationTenantLookup]); the write then runs with that tenant bound and the `sub` as the actor. No request field
 * names the tenant or the identity. A token that carries another tenant than the invitation's is answered as a wrong
 * code. A call with no verified end-user principal is 401 (SRT-003).
 */
class FirstAdministratorAcceptance(
    private val useCase: AcceptFirstAdministratorInvitation,
    private val lookup: InvitationTenantLookup,
    private val platform: PlatformTenantBinding,
    private val tenants: TenantContext,
    private val request: HttpServletRequest,
) {
    fun accept(
        invitationRef: String,
        body: AcceptFirstAdministratorInvitationRequest,
    ): ResponseEntity<AcceptFirstAdministratorInvitationResponse> {
        val principal = principalOf()
        val subject = principal.actor
        val tenant = tenantOf(invitationRef, principal)
        val command = AcceptFirstAdministratorCommand(invitationRef, body.invitationCode, subject)
        return when (val outcome = tenants.bind(tenant, subject) { useCase.handle(command) }) {
            is AcceptFirstAdministratorOutcome.Accepted -> ResponseEntity.ok(responseOf(outcome.grant))
            AcceptFirstAdministratorOutcome.NotFound -> throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
            else -> throw ApiConflict(conflictOf(outcome))
        }
    }

    /** The verified end-user principal; a call with none is 401 (SRT-003). */
    private fun principalOf(): EndUserPrincipal =
        request.getAttribute(EndUserPrincipalBindingFilter.PRINCIPAL_ATTRIBUTE) as? EndUserPrincipal
            ?: throw ApiRefusal(ClosedErrorCode.UNAUTHENTICATED)

    /** The detail of each 409 (no error code names these refusals, INV-Q-8845 A). */
    private fun conflictOf(outcome: AcceptFirstAdministratorOutcome): String =
        when (outcome) {
            AcceptFirstAdministratorOutcome.Expired -> {
                "the invitation has expired"
            }

            AcceptFirstAdministratorOutcome.AlreadyAccepted -> {
                "the invitation was accepted already"
            }

            AcceptFirstAdministratorOutcome.Withdrawn -> {
                "the invitation was withdrawn"
            }

            AcceptFirstAdministratorOutcome.SubjectAlreadyHolds -> {
                "the identity already holds this role in this scope"
            }

            is AcceptFirstAdministratorOutcome.Accepted, AcceptFirstAdministratorOutcome.NotFound -> {
                error("not a conflict")
            }
        }

    /** The tenant of the invitation, or the 404 of a wrong code (no invitation, or a token of another tenant). */
    private fun tenantOf(
        invitationRef: String,
        principal: EndUserPrincipal,
    ): TenantId {
        val found = platform.bindPlatform(principal.actor) { lookup.tenantOf(invitationRef) }
        if (found == null || (principal.tenant != null && principal.tenant != found)) {
            throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
        }
        return found
    }

    private fun responseOf(grant: Grant) =
        AcceptFirstAdministratorInvitationResponse(
            grantRef = grant.ref.value,
            identityRef = grant.identity.value,
            role = grant.role.wire,
            // the grant of an invitation is always Tenant-scope (ProvisionFirstAdministrator)
            scope = "tenant:${grant.tenant.value}",
            status = AcceptFirstAdministratorInvitationResponse.Status.valueOf(grant.status.name),
            grantSource = AcceptFirstAdministratorInvitationResponse.GrantSource.valueOf(grant.source.name),
        )
}
