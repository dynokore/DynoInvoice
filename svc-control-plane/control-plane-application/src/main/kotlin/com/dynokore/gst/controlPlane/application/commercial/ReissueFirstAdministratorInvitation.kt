package com.dynokore.gst.controlPlane.application.commercial

import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.application.release.OperatorPermissions
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.commercial.ProvisioningStatus
import com.dynokore.gst.controlPlane.domain.commercial.TenantProvisioning
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.platform.serviceruntime.operation.DeclaredOperation
import com.dynokore.platform.serviceruntime.operation.OperationAudit
import com.dynokore.platform.serviceruntime.operation.Reversibility
import java.time.temporal.ChronoUnit
import java.util.UUID

data class ReissueInvitationCommand(
    val actor: ActorId,
    val contractReference: CommercialRef,
    val idempotencyKey: UUID,
    val request: RequestContext,
)

/** What `requestFirstAdministratorInvitationReissue` reads: the provisioning request and the attempt of the key. */
data class ReissueState(
    val provisioning: ProvisioningRecord?,
    val attempt: ReissueAttempt?,
)

/** The answer of the reissue; it never holds the invitation code. */
sealed interface ReissueResult {
    data class Issued(
        val contractReference: CommercialRef,
        val tenantRef: CommercialRef,
        val invitationRef: String,
        val invitationExpiresAt: java.time.Instant,
    ) : ReissueResult

    /** access-svc answered 409: nothing was sent. */
    data object Refused : ReissueResult

    /** access-svc was unreachable or answered 5xx: nothing was sent; a retry with the same key repeats the request. */
    data object Unavailable : ReissueResult

    /** access-svc answered another 5xx or an answer its contract does not define: nothing was sent (500 SRT-010). */
    data object Failed : ReissueResult
}

/**
 * requestFirstAdministratorInvitationReissue (INV-AC-8281, INV-Q-8232 A): `commercial.admin` asks for a new signed
 * invitation for a provisioned tenant whose first invitation expired unused. access-svc keeps the same pending grant
 * and issues a new reference, code and the requested expiry; the new signed invitation is the only message that
 * carries the code. The repeat key is the Idempotency-Key.
 */
class ReissueFirstAdministratorInvitation(
    private val provisionings: ProvisioningRepository,
    private val attempts: ReissueAttemptRepository,
    private val invitation: InvitationDelivery,
    private val permissions: OperatorPermissions,
) : DeclaredOperation<ReissueInvitationCommand, ReissueState, ReissueResult> {
    override val operationId = "requestFirstAdministratorInvitationReissue"
    override val reversibility = Reversibility.IRREVERSIBLE
    override val audited = true

    override fun permits(
        actor: String,
        command: ReissueInvitationCommand,
    ) = permissions.holds(actor, PERMISSION_KEY) && actor == command.actor.value

    override fun repeatKey(command: ReissueInvitationCommand) = command.idempotencyKey.toString()

    override fun load(command: ReissueInvitationCommand) =
        ReissueState(
            provisionings.findByContract(command.contractReference),
            attempts.find(command.idempotencyKey, command.actor.value),
        )

    override fun priorResult(
        command: ReissueInvitationCommand,
        state: ReissueState,
    ): ReissueResult? {
        val attempt = state.attempt
        val provisioning = state.provisioning?.provisioning
        val done = attempt?.invitationRef
        return if (attempt?.contractReference == command.contractReference && provisioning != null && done != null) {
            ReissueResult.Issued(command.contractReference, provisioning.tenantRef, done, attempt.invitationExpiresAt)
        } else {
            null
        }
    }

    override fun unmetPrecondition(
        command: ReissueInvitationCommand,
        state: ReissueState,
    ): String? =
        when {
            state.attempt != null && state.attempt.contractReference != command.contractReference -> DIFFERENT_REQUEST
            state.provisioning == null -> NOT_FOUND
            state.provisioning.provisioning.status != ProvisioningStatus.CREATED -> NOT_CREATED
            else -> null
        }

    override fun perform(
        command: ReissueInvitationCommand,
        state: ReissueState,
    ): ReissueResult {
        val provisioning = checkNotNull(state.provisioning).provisioning
        val attempt =
            state.attempt
                ?: ReissueAttempt(
                    command.idempotencyKey,
                    command.contractReference,
                    invitation.clock
                        .instant()
                        .truncatedTo(ChronoUnit.SECONDS)
                        .plus(invitation.validity),
                    null,
                ).also { attempts.save(it, command.actor.value) }
        return when (
            val answer =
                invitation.access.reissue(
                    ReissueFirstAdministratorCall(
                        provisioning.tenantRef,
                        attempt.invitationExpiresAt,
                        attempt.idempotencyKey,
                    ),
                )
        ) {
            is FirstAdministratorAnswer.Issued -> {
                send(command, provisioning, attempt, answer)
            }

            FirstAdministratorAnswer.Refused -> {
                ReissueResult.Refused
            }

            FirstAdministratorAnswer.Unavailable -> {
                ReissueResult.Unavailable
            }

            FirstAdministratorAnswer.Failed -> {
                ReissueResult.Failed
            }
        }
    }

    private fun send(
        command: ReissueInvitationCommand,
        provisioning: TenantProvisioning,
        attempt: ReissueAttempt,
        answer: FirstAdministratorAnswer.Issued,
    ): ReissueResult {
        val token =
            invitation.issuer.issue(
                provisioning.contractReference,
                provisioning.tenantRef,
                answer,
                invitation.clock.instant().truncatedTo(ChronoUnit.SECONDS),
                attempt.invitationExpiresAt,
            )
        attempts.complete(attempt.idempotencyKey, command.actor.value, answer.invitationRef)
        invitation.messages.request(
            provisioning.contractReference,
            provisioning.firstAdministratorEmail,
            token,
            command.request,
        )
        return ReissueResult.Issued(
            provisioning.contractReference,
            provisioning.tenantRef,
            answer.invitationRef,
            attempt.invitationExpiresAt,
        )
    }

    override fun audit(
        command: ReissueInvitationCommand,
        state: ReissueState,
        result: ReissueResult,
    ) = OperationAudit("platform", null, null, auditContext(command.contractReference.value, command.request))

    companion object {
        /** The WRITE key in platform scope (INV-Q-16217 A), decided per request, never a role name. */
        const val PERMISSION_KEY = "tenant-invitation.reissue"

        /** Refusal codes the runner carries; the controller maps each (the 404 and 409 of the contract). */
        const val NOT_FOUND = "NOT_FOUND"
        const val NOT_CREATED = "NOT_CREATED"
        const val DIFFERENT_REQUEST = ProvisionTenant.DIFFERENT_REQUEST
    }
}
