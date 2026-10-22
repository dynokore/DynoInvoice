package com.dynokore.gst.controlPlane.application.commercial

import com.dynokore.gst.controlPlane.application.pack.OperatorRole
import com.dynokore.gst.controlPlane.application.pack.OperatorRoles
import com.dynokore.gst.controlPlane.application.pack.RequestContext
import com.dynokore.gst.controlPlane.domain.commercial.CommercialRef
import com.dynokore.gst.controlPlane.domain.commercial.Entitlements
import com.dynokore.gst.controlPlane.domain.commercial.Plan
import com.dynokore.gst.controlPlane.domain.commercial.PlanVersion
import com.dynokore.gst.controlPlane.domain.commercial.Qualification
import com.dynokore.gst.controlPlane.domain.commercial.QualificationOutcome
import com.dynokore.gst.controlPlane.domain.pack.ActorId
import com.dynokore.platform.outboxandqueue.CausationId
import com.dynokore.platform.outboxandqueue.CorrelationId
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.outboxandqueue.EventContext
import com.dynokore.platform.serviceruntime.operation.DeclaredOperation
import com.dynokore.platform.serviceruntime.operation.OperationAudit
import com.dynokore.platform.serviceruntime.operation.Reversibility
import com.dynokore.platform.tenancykernel.TenantId

private const val SCOPE = "installation"

private fun auditContext(
    aggregateKey: String,
    request: RequestContext,
) = EventContext(
    tenant = TenantId.PLATFORM,
    entity = EntityRef.PLATFORM,
    docUuid = null,
    transactionDate = null,
    aggregateKey = aggregateKey,
    aggregateVersion = null,
    correlation = CorrelationId(request.correlationId),
    causation = CausationId(request.causationId),
)

data class ManagePlanCommand(
    val actor: ActorId,
    val tenantRef: CommercialRef,
    val planVersion: PlanVersion,
    val entitlements: Entitlements,
    val request: RequestContext,
)

/**
 * managePlan (SCR-38.2, INV-REQ-906 b1): `commercial.admin` stores a version of a tenant's plan with its entitlements.
 * The repeat key is (tenantRef, planVersion): a repeat returns the stored version unchanged. Entitlement is advisory
 * (INV-ADR-272), so nothing here limits a request.
 */
class ManagePlan(
    private val plans: PlanRepository,
    private val roles: OperatorRoles,
) : DeclaredOperation<ManagePlanCommand, Plan?, Plan> {
    override val operationId = "managePlan"
    override val reversibility = Reversibility.UNDOABLE
    override val audited = true

    override fun permits(
        actor: String,
        command: ManagePlanCommand,
    ) = roles.holds(actor, OperatorRole.COMMERCIAL_ADMIN) && actor == command.actor.value

    override fun repeatKey(command: ManagePlanCommand) = "${command.tenantRef.value}:${command.planVersion.value}"

    override fun load(command: ManagePlanCommand) = plans.find(command.tenantRef, command.planVersion)

    override fun priorResult(
        command: ManagePlanCommand,
        state: Plan?,
    ) = state

    override fun unmetPrecondition(
        command: ManagePlanCommand,
        state: Plan?,
    ): String? = null

    override fun perform(
        command: ManagePlanCommand,
        state: Plan?,
    ): Plan =
        Plan(command.tenantRef, command.planVersion, command.entitlements).also {
            plans.save(it, command.actor.value)
        }

    override fun audit(
        command: ManagePlanCommand,
        state: Plan?,
        result: Plan,
    ) = OperationAudit(SCOPE, null, null, auditContext(repeatKey(command), command.request))
}

data class RecordQualificationCommand(
    val actor: ActorId,
    val customerRef: CommercialRef,
    val installationRef: CommercialRef,
    val outcome: QualificationOutcome,
    val request: RequestContext,
)

/**
 * recordQualification (SCR-38.4, INV-REQ-906 b1): `commercial.admin` records the qualification outcome of an
 * installation. The repeat key is (customerRef, installationRef): a repeat returns the recorded outcome unchanged.
 */
class RecordQualification(
    private val qualifications: QualificationRepository,
    private val roles: OperatorRoles,
) : DeclaredOperation<RecordQualificationCommand, Qualification?, Qualification> {
    override val operationId = "recordQualification"
    override val reversibility = Reversibility.UNDOABLE
    override val audited = true

    override fun permits(
        actor: String,
        command: RecordQualificationCommand,
    ) = roles.holds(actor, OperatorRole.COMMERCIAL_ADMIN) && actor == command.actor.value

    override fun repeatKey(command: RecordQualificationCommand) =
        "${command.customerRef.value}:${command.installationRef.value}"

    override fun load(command: RecordQualificationCommand) =
        qualifications.find(command.customerRef, command.installationRef)

    override fun priorResult(
        command: RecordQualificationCommand,
        state: Qualification?,
    ) = state

    override fun unmetPrecondition(
        command: RecordQualificationCommand,
        state: Qualification?,
    ): String? = null

    override fun perform(
        command: RecordQualificationCommand,
        state: Qualification?,
    ): Qualification =
        Qualification(command.customerRef, command.installationRef, command.outcome)
            .also { qualifications.save(it, command.actor.value) }

    override fun audit(
        command: RecordQualificationCommand,
        state: Qualification?,
        result: Qualification,
    ) = OperationAudit(SCOPE, null, null, auditContext(repeatKey(command), command.request))
}
