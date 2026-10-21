package com.dynokore.gst.access.adapters.http

import com.dynokore.gst.access.application.separation.RecordSoleOperatorOutcome
import com.dynokore.gst.access.application.separation.RecordSoleOperatorUseCase
import com.dynokore.gst.access.application.separation.WithdrawSoleOperatorOutcome
import com.dynokore.gst.access.application.separation.WithdrawSoleOperatorUseCase
import com.dynokore.gst.access.contract.api.SeparationOfDutyApi
import com.dynokore.gst.access.contract.model.SoleOperatorAckRequest
import com.dynokore.gst.access.contract.model.SoleOperatorAckResponse
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.tenancykernel.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * TPL-08. recordSoleOperator and withdrawSoleOperatorAcknowledgement (INV-REQ-891, INV-REQ-994): `tenant.admin` only
 * ([GrantManagerCheck]), for the bound tenant only. A repeat of recordSoleOperator returns the active acknowledgement.
 */
@RestController
// open: the generated API interface is @Validated, so Spring proxies the controller (no kotlin-spring plugin in the
// template)
open class SoleOperatorController(
    private val record: RecordSoleOperatorUseCase,
    private val withdraw: WithdrawSoleOperatorUseCase,
    private val tenants: TenantContext,
    private val manager: GrantManagerCheck,
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

    override fun withdrawSoleOperatorAcknowledgement(
        ackRef: String,
        idempotencyKey: UUID,
    ): ResponseEntity<Unit> {
        requireManager()
        val actor = checkNotNull(tenants.currentActor()) { "no actor is bound" }
        return when (withdraw.handle(actor, ackRef)) {
            WithdrawSoleOperatorOutcome.Withdrawn, WithdrawSoleOperatorOutcome.AlreadyWithdrawn -> {
                ResponseEntity.noContent().build()
            }

            WithdrawSoleOperatorOutcome.NotFound -> {
                throw ApiRefusal(ClosedErrorCode.NOT_FOUND)
            }
        }
    }

    private fun requireManager() {
        if (!manager.mayManageGrants()) throw ApiRefusal(ClosedErrorCode.ACCESS_DENIED)
    }
}
