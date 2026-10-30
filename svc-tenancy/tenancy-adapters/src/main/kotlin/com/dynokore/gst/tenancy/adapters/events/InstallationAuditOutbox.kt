package com.dynokore.gst.tenancy.adapters.events

import com.dynokore.gst.tenancy.application.installation.InstallationAudit
import com.dynokore.gst.tenancy.application.installation.InstallationStep
import com.dynokore.platform.auditlog.AuditEntry
import com.dynokore.platform.auditlog.AuditWriter
import com.dynokore.platform.outboxandqueue.EntityRef
import com.dynokore.platform.tenancykernel.TenantId
import java.util.UUID

/**
 * INV-REQ-861, INV-Q-3245: writes the AuditEntryRecorded of an installer step through audit-log into tenancy-svc's own
 * outbox, in the transaction the caller holds, under the reserved platform TenantId (INV-Q-2927). The actor is the
 * identity that ran the command; the reason names the deployment.operator and, in SaaS, the approving platform.engineer
 * with the run and the step. The entry carries only bounded request fields: no free-form input reaches it (API-49).
 */
class InstallationAuditOutbox(
    private val writer: AuditWriter,
) : InstallationAudit {
    override fun record(
        step: InstallationStep,
        priorStateJson: String?,
    ): UUID {
        val entry =
            AuditEntry(
                actor = step.runIdentity,
                action = "installation.${step.command}",
                scope = SCOPE,
                reason = reason(step),
                priorStateJson = priorStateJson,
            )
        val envelope = writer.record(entry, EventContexts.forAggregate(TenantId.PLATFORM, EntityRef.PLATFORM, SCOPE))
        return UUID.fromString(envelope.id.value)
    }

    private fun reason(step: InstallationStep): String =
        listOfNotNull(
            "runId=${step.runId}",
            "step=${step.step}",
            "stepName=${step.stepName}",
            "outcome=${step.outcome}",
            "deploymentOperator=${step.deploymentOperator}",
            step.approvingEngineer?.let { "approvingEngineer=$it" },
            step.trainFrom?.let { "trainFrom=$it" },
            step.trainTo?.let { "trainTo=$it" },
        ).joinToString(" ")

    private companion object {
        const val SCOPE = "installation"
    }
}
