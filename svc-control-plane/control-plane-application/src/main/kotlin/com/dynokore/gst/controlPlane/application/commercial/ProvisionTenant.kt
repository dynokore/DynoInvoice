package com.dynokore.gst.controlPlane.application.commercial

import com.dynokore.gst.controlPlane.application.pack.OperatorRole
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.commercial.PlanRef
import com.dynokore.gst.controlPlane.domain.commercial.ProvisioningStatus
import com.dynokore.gst.controlPlane.domain.commercial.RegionKey
import com.dynokore.gst.controlPlane.domain.commercial.TenantProvisioning
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.platform.serviceruntime.operation.DeclaredOperation
import com.dynokore.platform.serviceruntime.operation.OperationAudit
import com.dynokore.platform.serviceruntime.operation.Reversibility
import java.time.Clock
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.UUID

data class ProvisionTenantCommand(
    val actor: ActorId,
    val contractReference: CommercialRef,
    val legalName: String,
    val planRef: PlanRef,
    val firstAdministratorEmail: String,
    val dataRegion: RegionKey,
    val idempotencyKey: UUID,
    /** A digest of the whole request, so that a key reused with another request is told apart (API-15). */
    val fingerprint: String,
    val credential: CallerCredential,
    val request: RequestContext,
)

/** What `provisionTenant` reads before it decides: the request of the contract, the request of the key, the region. */
data class ProvisionState(
    val byContract: ProvisioningRecord?,
    val byKey: ProvisioningRecord?,
    val regionListed: Boolean,
)

/** How the first administrator's invitation is issued, announced and timed. */
class InvitationDelivery(
    val issuer: InvitationIssuer,
    val messages: InvitationMessages,
    val clock: Clock,
    val validity: Duration,
) {
    init {
        require(!validity.isZero && !validity.isNegative) { "an invitation expires after its issue" }
    }
}

/**
 * provisionTenant (SCR-38.1, INV-REQ-1306, INV-API-3495): `commercial.admin` provisions a SaaS tenant from a contract.
 * The region is elected here with no default and must be on the region list (CPL-003); it is recorded with the request
 * and sent to tenancy-svc, and no operation changes it afterwards. The repeat key is the contract reference: a repeat
 * returns the original request whatever the region of the repeat. The Idempotency-Key reused with another request is
 * refused (SRT-009). `planRef` is an opaque reference passed on unchanged; no plan lookup is made (INV-Q-8691).
 */
class ProvisionTenant(
    private val provisionings: ProvisioningRepository,
    private val regions: RegionList,
    private val tenants: TenantCreator,
    private val invitation: InvitationDelivery,
    private val roles: OperatorRoles,
    private val newTenantRef: () -> CommercialRef,
) : DeclaredOperation<ProvisionTenantCommand, ProvisionState, TenantProvisioning> {
    override val operationId = "provisionTenant"
    override val reversibility = Reversibility.COMPENSABLE
    override val audited = true

    override fun permits(
        actor: String,
        command: ProvisionTenantCommand,
    ) = roles.holds(actor, OperatorRole.COMMERCIAL_ADMIN) && actor == command.actor.value

    override fun repeatKey(command: ProvisionTenantCommand) = command.contractReference.value

    override fun load(command: ProvisionTenantCommand) =
        ProvisionState(
            provisionings.findByContract(command.contractReference),
            provisionings.findByKey(command.idempotencyKey, command.actor.value),
            regions.contains(command.dataRegion),
        )

    override fun priorResult(
        command: ProvisionTenantCommand,
        state: ProvisionState,
    ): TenantProvisioning? =
        when {
            state.byKey != null && state.byKey.fingerprint != command.fingerprint -> null
            state.byKey != null -> state.byKey.provisioning
            else -> state.byContract?.provisioning
        }

    override fun unmetPrecondition(
        command: ProvisionTenantCommand,
        state: ProvisionState,
    ): String? =
        when {
            state.byKey != null -> DIFFERENT_REQUEST
            !state.regionListed -> REGION_NOT_LISTED
            else -> null
        }

    override fun perform(
        command: ProvisionTenantCommand,
        state: ProvisionState,
    ): TenantProvisioning {
        val tenantRef = newTenantRef()
        val issuedAt = invitation.clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val expiresAt = issuedAt.plus(invitation.validity)
        val invitationRef = invitation.issuer.issue(command.contractReference, tenantRef, issuedAt, expiresAt)
        val recorded =
            TenantProvisioning(
                command.contractReference,
                tenantRef,
                command.legalName,
                command.planRef,
                command.firstAdministratorEmail,
                command.dataRegion,
                ProvisioningStatus.PENDING,
                invitationRef,
                expiresAt,
            )
        provisionings.save(
            ProvisioningRecord(recorded, command.idempotencyKey, command.fingerprint),
            command.actor.value,
        )
        invitation.messages.request(
            command.contractReference,
            command.firstAdministratorEmail,
            invitationRef,
            command.request,
        )
        val created = createTenant(tenantRef, command)
        if (created != TenantCreation.Created) return recorded
        provisionings.markCreated(command.contractReference)
        return recorded.copy(status = ProvisioningStatus.CREATED)
    }

    private fun createTenant(
        tenantRef: CommercialRef,
        command: ProvisionTenantCommand,
    ) = tenants.create(
        CreateTenantCall(
            tenantRef,
            command.contractReference,
            command.legalName,
            command.planRef,
            command.dataRegion,
            command.credential,
        ),
    )

    override fun audit(
        command: ProvisionTenantCommand,
        state: ProvisionState,
        result: TenantProvisioning,
    ) = OperationAudit("platform", null, null, auditContext(repeatKey(command), command.request))

    companion object {
        /** The refusal codes the runner carries: the SRT code of API-15 and the catalogue code of the region list. */
        const val DIFFERENT_REQUEST = "SRT-009"
        const val REGION_NOT_LISTED = "CPL-003"
    }
}

/** getTenantProvisioningRequest: the status read of a provisioning request, by its contract reference. */
class GetTenantProvisioningRequest(
    private val provisionings: ProvisioningRepository,
) {
    fun get(contractReference: CommercialRef): TenantProvisioning? =
        provisionings.findByContract(contractReference)?.provisioning
}
