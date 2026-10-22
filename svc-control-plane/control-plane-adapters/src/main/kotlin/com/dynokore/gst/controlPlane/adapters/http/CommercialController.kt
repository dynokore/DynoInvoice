package com.dynokore.gst.controlPlane.adapters.http

import com.dynokore.gst.controlPlane.application.commercial.ManagePlan
import com.dynokore.gst.controlPlane.application.commercial.ManagePlanCommand
import com.dynokore.gst.controlPlane.application.commercial.RecordQualification
import com.dynokore.gst.controlPlane.application.commercial.RecordQualificationCommand
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.contract.api.CommercialApi
import com.dynokore.gst.controlPlane.contract.model.ManagePlanRequest
import com.dynokore.gst.controlPlane.contract.model.ManagePlanRequestEntitlements
import com.dynokore.gst.controlPlane.contract.model.PlanResponse
import com.dynokore.gst.controlPlane.contract.model.QualificationResponse
import com.dynokore.gst.controlPlane.contract.model.RecordQualificationRequest
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.commercial.Entitlements
import com.dynokore.gst.controlPlane.domain.commercial.Plan
import com.dynokore.gst.controlPlane.domain.commercial.PlanVersion
import com.dynokore.gst.controlPlane.domain.commercial.Qualification
import com.dynokore.gst.controlPlane.domain.commercial.QualificationOutcome
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.operation.OperationOutcome
import com.dynokore.platform.serviceruntime.operation.OperationRunner
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * managePlan and recordQualification of `commercial.admin` (INV-REQ-906 b1, TPL-08). The other operations of the
 * commercial tag are not overridden here, so the generated interface answers 501 for them; `operations.tsv` lists each
 * under its own state. The caller is the verified token's `sub`, never a request field (API-24).
 */
@RestController
class CommercialController(
    private val principals: EndUserPrincipalResolver,
    private val platform: PlatformTenantBinding,
    private val runner: OperationRunner,
    private val managePlan: ManagePlan,
    private val recordQualification: RecordQualification,
) : CommercialApi {
    override fun managePlan(
        idempotencyKey: UUID,
        managePlanRequest: ManagePlanRequest,
    ): ResponseEntity<PlanResponse> {
        val caller = OperatorCaller.of(principals)
        val actor = caller.principal.actor
        val command =
            caller.commandOf {
                ManagePlanCommand(
                    ActorId(actor),
                    CommercialRef(managePlanRequest.tenantRef),
                    PlanVersion(managePlanRequest.planVersion),
                    managePlanRequest.entitlements.let { Entitlements(it.maxEntities, it.maxDocumentsPerMonth) },
                    RequestContext(caller.correlationId, idempotencyKey.toString()),
                )
            }
        val outcome = platform.bindPlatform(actor) { runner.run(managePlan, actor, command) }
        return ResponseEntity.status(HttpStatus.CREATED).body(planOf(result(outcome, caller)))
    }

    override fun recordQualification(
        idempotencyKey: UUID,
        recordQualificationRequest: RecordQualificationRequest,
    ): ResponseEntity<QualificationResponse> {
        val caller = OperatorCaller.of(principals)
        val actor = caller.principal.actor
        val command =
            caller.commandOf {
                RecordQualificationCommand(
                    ActorId(actor),
                    CommercialRef(recordQualificationRequest.customerRef),
                    CommercialRef(recordQualificationRequest.installationRef),
                    QualificationOutcome.valueOf(recordQualificationRequest.outcome.name),
                    RequestContext(caller.correlationId, idempotencyKey.toString()),
                )
            }
        val outcome = platform.bindPlatform(actor) { runner.run(recordQualification, actor, command) }
        return ResponseEntity.status(HttpStatus.CREATED).body(qualificationOf(result(outcome, caller)))
    }

    private fun <R> result(
        outcome: OperationOutcome<R>,
        caller: OperatorCaller,
    ): R =
        when (outcome) {
            is OperationOutcome.Performed -> outcome.result
            is OperationOutcome.Repeated -> outcome.result
            OperationOutcome.NotPermitted -> problem(ClosedErrorCode.ACCESS_DENIED, caller.correlationId)
            is OperationOutcome.PreconditionUnmet -> error("no precondition is declared: ${outcome.code}")
        }

    private fun planOf(plan: Plan) =
        PlanResponse(
            plan.tenantRef.value,
            plan.version.value,
            ManagePlanRequestEntitlements(plan.entitlements.maxEntities, plan.entitlements.maxDocumentsPerMonth),
        )

    private fun qualificationOf(q: Qualification) =
        QualificationResponse(
            q.customerRef.value,
            q.installationRef.value,
            QualificationResponse.Outcome.valueOf(q.outcome.name),
        )
}
