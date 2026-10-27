package com.dynokore.gst.controlPlane.adapters.http

import com.dynokore.gst.controlPlane.adapters.bundle.CanonicalJson
import com.dynokore.gst.controlPlane.application.commercial.CallerCredential
import com.dynokore.gst.controlPlane.application.commercial.GetLicence
import com.dynokore.gst.controlPlane.application.commercial.GetTenantProvisioningRequest
import com.dynokore.gst.controlPlane.application.commercial.IssueLicence
import com.dynokore.gst.controlPlane.application.commercial.IssueLicenceCommand
import com.dynokore.gst.controlPlane.application.commercial.ManagePlan
import com.dynokore.gst.controlPlane.application.commercial.ManagePlanCommand
import com.dynokore.gst.controlPlane.application.commercial.ProvisionTenant
import com.dynokore.gst.controlPlane.application.commercial.ProvisionTenantCommand
import com.dynokore.gst.controlPlane.application.commercial.RecordQualification
import com.dynokore.gst.controlPlane.application.commercial.RecordQualificationCommand
import com.dynokore.gst.controlPlane.application.pack.OperatorRole
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.contract.api.CommercialApi
import com.dynokore.gst.controlPlane.contract.model.IssueLicenceRequest
import com.dynokore.gst.controlPlane.contract.model.LicenceIssueResponse
import com.dynokore.gst.controlPlane.contract.model.ManagePlanRequest
import com.dynokore.gst.controlPlane.contract.model.ManagePlanRequestEntitlements
import com.dynokore.gst.controlPlane.contract.model.PlanResponse
import com.dynokore.gst.controlPlane.contract.model.ProvisionTenantRequest
import com.dynokore.gst.controlPlane.contract.model.QualificationResponse
import com.dynokore.gst.controlPlane.contract.model.RecordQualificationRequest
import com.dynokore.gst.controlPlane.contract.model.TenantProvisioningResponse
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.commercial.Entitlements
import com.dynokore.gst.controlPlane.domain.commercial.Licence
import com.dynokore.gst.controlPlane.domain.commercial.Plan
import com.dynokore.gst.controlPlane.domain.commercial.PlanRef
import com.dynokore.gst.controlPlane.domain.commercial.PlanVersion
import com.dynokore.gst.controlPlane.domain.commercial.ProvisioningStatus
import com.dynokore.gst.controlPlane.domain.commercial.Qualification
import com.dynokore.gst.controlPlane.domain.commercial.QualificationOutcome
import com.dynokore.gst.controlPlane.domain.commercial.RegionKey
import com.dynokore.gst.controlPlane.domain.commercial.TenantProvisioning
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.platform.serviceruntime.http.ClosedErrorCode
import com.dynokore.platform.serviceruntime.identity.EndUserPrincipalResolver
import com.dynokore.platform.serviceruntime.operation.OperationOutcome
import com.dynokore.platform.serviceruntime.operation.OperationRunner
import com.dynokore.platform.tenancykernel.PlatformTenantBinding
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/**
 * The commercial operations of `commercial.admin` (INV-REQ-906 b1, INV-REQ-1306, TPL-08): managePlan,
 * recordQualification, issueLicence, getLicence, provisionTenant and getTenantProvisioningRequest. The other
 * operations of the commercial tag are not overridden here, so the generated interface answers 501 for them;
 * `operations.tsv` lists each under its own state. The caller is the verified token's `sub`, never a request field (API-24).
 */
@RestController
class CommercialController(
    private val principals: EndUserPrincipalResolver,
    private val platform: PlatformTenantBinding,
    private val transactions: TransactionRunner,
    private val runner: OperationRunner,
    private val roles: OperatorRoles,
    private val managePlan: ManagePlan,
    private val recordQualification: RecordQualification,
    private val provisionTenant: ProvisionTenant,
    private val getProvisioning: GetTenantProvisioningRequest,
    private val issueLicence: IssueLicence,
    private val getLicence: GetLicence,
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

    override fun provisionTenant(
        idempotencyKey: UUID,
        provisionTenantRequest: ProvisionTenantRequest,
    ): ResponseEntity<TenantProvisioningResponse> {
        val caller = OperatorCaller.of(principals)
        val actor = caller.principal.actor
        val command =
            caller.commandOf {
                ProvisionTenantCommand(
                    ActorId(actor),
                    CommercialRef(provisionTenantRequest.contractReference),
                    provisionTenantRequest.legalName,
                    PlanRef(provisionTenantRequest.planRef),
                    provisionTenantRequest.firstAdministratorEmail,
                    RegionKey(provisionTenantRequest.dataRegion),
                    idempotencyKey,
                    fingerprintOf(provisionTenantRequest),
                    CallerCredential(caller.authorization),
                    RequestContext(caller.correlationId, idempotencyKey.toString()),
                )
            }
        val outcome = platform.bindPlatform(actor) { runner.run(provisionTenant, actor, command) }
        val provisioning = result(outcome, caller, command.dataRegion)
        return ResponseEntity
            .created(URI.create("$PROVISIONING_PATH/${provisioning.contractReference.value}"))
            .body(provisioningOf(provisioning))
    }

    override fun getTenantProvisioningRequest(contractReference: String): ResponseEntity<TenantProvisioningResponse> {
        val caller = OperatorCaller.of(principals)
        val actor = caller.principal.actor
        requireCommercialAdmin(caller)
        val ref = caller.commandOf { CommercialRef(contractReference) }
        val found = platform.bindPlatform(actor) { transactions.inTransaction { getProvisioning.get(ref) } }
        return ResponseEntity.ok(provisioningOf(found ?: problem(ClosedErrorCode.NOT_FOUND, caller.correlationId)))
    }

    override fun issueLicence(
        idempotencyKey: UUID,
        issueLicenceRequest: IssueLicenceRequest,
    ): ResponseEntity<LicenceIssueResponse> {
        val caller = OperatorCaller.of(principals)
        val actor = caller.principal.actor
        val command =
            caller.commandOf {
                IssueLicenceCommand(
                    ActorId(actor),
                    CommercialRef(issueLicenceRequest.installationRef),
                    issueLicenceRequest.term.also {
                        require(
                            it.length in 1..Licence.MAX_TERM_LENGTH,
                        ) { "term is 1..32" }
                    },
                    RequestContext(caller.correlationId, idempotencyKey.toString()),
                )
            }
        val outcome = platform.bindPlatform(actor) { runner.run(issueLicence, actor, command) }
        return ResponseEntity.status(HttpStatus.CREATED).body(licenceOf(result(outcome, caller)))
    }

    override fun getLicence(licenceRef: String): ResponseEntity<LicenceIssueResponse> {
        val caller = OperatorCaller.of(principals)
        val actor = caller.principal.actor
        requireCommercialAdmin(caller)
        val ref = caller.commandOf { CommercialRef(licenceRef) }
        val found = platform.bindPlatform(actor) { transactions.inTransaction { getLicence.get(ref) } }
        return ResponseEntity.ok(licenceOf(found ?: problem(ClosedErrorCode.NOT_FOUND, caller.correlationId)))
    }

    private fun requireCommercialAdmin(caller: OperatorCaller) {
        if (!roles.holds(caller.principal.actor, OperatorRole.COMMERCIAL_ADMIN)) {
            problem(ClosedErrorCode.ACCESS_DENIED, caller.correlationId)
        }
    }

    private fun <R> result(
        outcome: OperationOutcome<R>,
        caller: OperatorCaller,
        region: RegionKey? = null,
    ): R =
        when (outcome) {
            is OperationOutcome.Performed -> outcome.result
            is OperationOutcome.Repeated -> outcome.result
            OperationOutcome.NotPermitted -> problem(ClosedErrorCode.ACCESS_DENIED, caller.correlationId)
            is OperationOutcome.PreconditionUnmet -> refuse(outcome.code, caller.correlationId, region)
        }

    private fun refuse(
        code: String,
        correlationId: String,
        region: RegionKey?,
    ): Nothing =
        when (code) {
            ProvisionTenant.DIFFERENT_REQUEST -> {
                problem(ClosedErrorCode.DIFFERENT_REQUEST, correlationId)
            }

            ProvisionTenant.REGION_NOT_LISTED -> {
                throw ProblemException(RegionNotListed.render(checkNotNull(region), correlationId))
            }

            IssueLicence.QUALIFICATION_NOT_RECORDED -> {
                problem(
                    ClosedErrorCode.CONTRACT_VIOLATION,
                    correlationId,
                    "No QUALIFIED outcome is recorded for the installation",
                )
            }

            else -> {
                error("an undeclared refusal: $code")
            }
        }

    private fun fingerprintOf(request: ProvisionTenantRequest): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(CanonicalJson.bytes(request)))

    private fun provisioningOf(p: TenantProvisioning) =
        TenantProvisioningResponse(
            p.contractReference.value,
            p.tenantRef.value,
            when (p.status) {
                ProvisioningStatus.PENDING -> TenantProvisioningResponse.Status.PENDING
                ProvisioningStatus.CREATED -> TenantProvisioningResponse.Status.CREATED
            },
            p.dataRegion.value,
        )

    private fun licenceOf(l: Licence) =
        LicenceIssueResponse(l.licenceRef.value, l.installationRef.value, l.term, l.signedFile)

    private companion object {
        const val PROVISIONING_PATH = "/internal/v1/control-plane/tenant-provisioning-requests"
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
