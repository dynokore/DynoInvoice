package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.separation.GetSoleOperatorAcknowledgementUseCase
import com.dynokore.gst.access.application.separation.RecordSoleOperatorOutcome
import com.dynokore.gst.access.application.separation.RecordSoleOperatorUseCase
import com.dynokore.gst.access.application.separation.WithdrawSoleOperatorOutcome
import com.dynokore.gst.access.application.separation.WithdrawSoleOperatorUseCase
import com.dynokore.gst.access.contract.api.SeparationOfDutyApi
import com.dynokore.gst.access.contract.model.InstallationApproverFact
import com.dynokore.gst.access.contract.model.InstallationApproverFactRequest
import com.dynokore.gst.access.contract.model.OverrideReleaseDecision
import com.dynokore.gst.access.contract.model.PackApprovalDecision
import com.dynokore.gst.access.contract.model.SoleOperatorAckRequest
import com.dynokore.gst.access.contract.model.SoleOperatorAckResponse
import com.dynokore.gst.access.contract.model.SoleOperatorAcknowledgementState
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.util.UUID

/**
 * TPL-08. recordSoleOperator and withdrawSoleOperatorAcknowledgement (INV-REQ-891, INV-REQ-994): `tenant.admin` only
 * ([GrantManagerCheck]), for the bound tenant only. A repeat of recordSoleOperator returns the active acknowledgement.
 * getSoleOperatorAcknowledgement (INV-REQ-875) is the read of the same stored acknowledgement.
 * The other operations of the tag `separation-of-duty` (INV-T-4936) are served by [ApprovalOperations]: one controller
 * implements the generated interface, so no mapping is registered twice.
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin in the
// template)
@Suppress("LongParameterList") // one collaborator per operation group of the tag
open class SoleOperatorController(
    private val record: RecordSoleOperatorUseCase,
    private val withdraw: WithdrawSoleOperatorUseCase,
    private val read: GetSoleOperatorAcknowledgementUseCase,
    private val tenants: TenantContext,
    private val manager: GrantManagerCheck,
    private val approvals: ApprovalOperations,
    private val installation: InstallationFactOperations,
) : SeparationOfDutyApi {
    override fun recordSoleOperator(
        idempotencyKey: UUID,
        soleOperatorAckRequest: SoleOperatorAckRequest,
    ): ResponseEntity<SoleOperatorAckResponse> {
        requireManager()
        val tenant = tenants.current().toString()
        // an acknowledgement is for the caller's own tenant only
        if (soleOperatorAckRequest.tenantRef != tenant) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound" }
        return when (val outcome = record.handle(actor, tenant, soleOperatorAckRequest.compensatingControls)) {
            is RecordSoleOperatorOutcome.Recorded -> {
                ResponseEntity
                    .status(if (outcome.repeated) HttpStatus.OK else HttpStatus.CREATED)
                    .body(
                        SoleOperatorAckResponse(
                            ackRef = outcome.ack.ref,
                            tenantRef = outcome.ack.tenantRef,
                            status = SoleOperatorAckResponse.Status.ACTIVE,
                        ),
                    )
            }

            RecordSoleOperatorOutcome.ControlsMissing -> {
                throw ApiRefusal(ClosedErrorCode.CONTRACT_VIOLATION, "compensatingControls must not be blank")
            }
        }
    }

    override fun getSoleOperatorAcknowledgement(tenantRef: String): ResponseEntity<SoleOperatorAcknowledgementState> {
        val reading = read.handle(tenants.current().toString(), tenantRef)
        return ResponseEntity.ok(SoleOperatorAcknowledgementState(tenantRef, reading.recorded, reading.ackRef))
    }

    override fun withdrawSoleOperatorAcknowledgement(
        ackRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<Unit> {
        requireManager()
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound" }
        return when (val outcome = withdraw.handle(actor, ackRef)) {
            WithdrawSoleOperatorOutcome.Withdrawn, WithdrawSoleOperatorOutcome.AlreadyWithdrawn -> {
                ResponseEntity.noContent().build()
            }

            WithdrawSoleOperatorOutcome.NotFound -> {
                throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
            }

            // the contract lists 403 and no 409 for this operation (INV-AC-8288 cases C6 and C7)
            WithdrawSoleOperatorOutcome.SecondPersonRequired,
            WithdrawSoleOperatorOutcome.HolderRemains,
            WithdrawSoleOperatorOutcome.OverrideSecondPersonRequired,
            WithdrawSoleOperatorOutcome.OverrideHolderRemains,
            -> {
                throw withdrawalRefused(outcome)
            }
        }
    }

    private fun withdrawalRefused(outcome: WithdrawSoleOperatorOutcome) =
        ApiRefusal(
            ClosedErrorCode.ACCESS_DENIED,
            when (outcome) {
                WithdrawSoleOperatorOutcome.SecondPersonRequired -> {
                    "a second person must hold security.officer or tenant.admin " +
                        "before the acknowledgement is withdrawn"
                }

                WithdrawSoleOperatorOutcome.OverrideSecondPersonRequired -> {
                    "a second person must hold tax.approver or tenant.admin before the acknowledgement is withdrawn"
                }

                WithdrawSoleOperatorOutcome.OverrideHolderRemains -> {
                    "a person still holds tax.approver and tenant.admin; revoke one of the roles first"
                }

                else -> {
                    "a person still holds security.officer and tenant.admin; revoke one of the roles first"
                }
            },
        )

    override fun decidePackApproval(
        entityRef: String,
        packRef: String,
        packVersion: String,
        authorRef: String,
        approverRef: String,
        priorApproverRef: List<String>?,
    ): ResponseEntity<PackApprovalDecision> =
        approvals.decidePackApproval(entityRef, authorRef, approverRef, priorApproverRef)

    override fun decideOverrideRelease(
        entityRef: String,
        errorCode: String,
        approverRef: String,
        documentRef: String?,
        documentVersion: Int?,
        documentTotalSgd: BigDecimal?,
        priorApproverRef: List<String>?,
    ): ResponseEntity<OverrideReleaseDecision> =
        approvals.decideOverrideRelease(
            entityRef,
            errorCode,
            approverRef,
            documentRef,
            documentVersion,
            documentTotalSgd,
            priorApproverRef,
        )

    override fun recordInstallationApproverFact(
        idempotencyKey: UUID,
        installationApproverFactRequest: InstallationApproverFactRequest,
    ): ResponseEntity<InstallationApproverFact> =
        installation.recordInstallationApproverFact(idempotencyKey, installationApproverFactRequest.distinctTaxApprover)

    override fun getInstallationApproverFact(): ResponseEntity<InstallationApproverFact> =
        installation.getInstallationApproverFact()

    private fun requireManager() {
        if (!manager.mayManageGrants()) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
    }
}
