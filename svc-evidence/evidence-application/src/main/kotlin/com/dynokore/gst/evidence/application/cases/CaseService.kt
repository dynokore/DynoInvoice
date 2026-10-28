package com.dynokore.gst.evidence.application.cases

import com.dynokore.gst.evidence.domain.cases.CaseKind
import com.dynokore.gst.evidence.domain.cases.CaseRecord
import com.dynokore.gst.evidence.domain.cases.CaseStatus
import com.dynokore.gst.evidence.domain.cases.SecurityCaseFacts
import com.dynokore.platform.serviceruntime.replica.Principal
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Clock
import java.util.UUID

/** `RecordSecurityCaseRequest` of the evidence contract. */
data class RecordSecurityCaseCommand(
    val reviewReference: String,
    val auditLogEntryIds: List<UUID>,
    val outcome: String,
    val personalDataExposed: Boolean,
)

/** The case use cases. Each asks the authorizer first; a refusal throws before any store call. */
class CaseService(
    private val store: CaseStore,
    private val authorizer: CaseAuthorizer,
    private val readPermission: String,
    private val ids: () -> UUID,
    private val clock: Clock,
) {
    fun recordSecurityCase(
        tenant: TenantId,
        principal: Principal,
        command: RecordSecurityCaseCommand,
    ): CaseRecord {
        authorizer.require(tenant, principal, CasePermissions.SECURITY_CASE_RECORD)
        val record =
            CaseRecord(
                caseId = ids(),
                kind = CaseKind.SECURITY,
                status = CaseStatus.OPEN,
                subjectRef = null,
                openedAt = clock.instant(),
                security =
                    SecurityCaseFacts(
                        reviewReference = command.reviewReference,
                        auditLogEntryIds = command.auditLogEntryIds,
                        outcome = command.outcome,
                        breachAssessmentOpened = command.personalDataExposed,
                    ),
            )
        store.insert(tenant, record)
        return record
    }

    fun getCase(
        tenant: TenantId,
        principal: Principal,
        caseId: UUID,
    ): CaseRecord? {
        authorizer.require(tenant, principal, readPermission)
        return store.find(tenant, caseId)
    }
}
