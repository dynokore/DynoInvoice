package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.CaseRegister.Companion.CASE_REGISTER
import com.dynokore.gst.evidence.application.cases.CaseStore
import com.dynokore.gst.evidence.domain.cases.CaseKind
import com.dynokore.gst.evidence.domain.cases.CaseRecord
import com.dynokore.gst.evidence.domain.cases.CaseStatus
import com.dynokore.gst.evidence.domain.cases.SecurityCaseFacts
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.ZoneOffset
import java.util.UUID

/** `case_register` over the service's tenant-bound [DSLContext] (jOOQ only, STK §3). */
class JooqCaseStore(
    private val dsl: DSLContext,
) : CaseStore {
    override fun insert(
        tenant: TenantId,
        case: CaseRecord,
    ) {
        val security = case.security
        dsl
            .insertInto(CASE_REGISTER)
            .set(CASE_REGISTER.TENANT_ID, tenant.value)
            .set(CASE_REGISTER.CASE_ID, case.caseId)
            .set(CASE_REGISTER.CASE_KIND, case.kind.name)
            .set(CASE_REGISTER.STATUS, case.status.name)
            .set(CASE_REGISTER.SUBJECT_REF, case.subjectRef)
            .set(CASE_REGISTER.OPENED_AT, case.openedAt.atOffset(ZoneOffset.UTC))
            .set(CASE_REGISTER.REVIEW_REFERENCE, security?.reviewReference)
            .set(CASE_REGISTER.AUDIT_LOG_ENTRY_IDS, security?.auditLogEntryIds?.toTypedArray())
            .set(CASE_REGISTER.OUTCOME, security?.outcome)
            .set(CASE_REGISTER.BREACH_ASSESSMENT_OPENED, security?.breachAssessmentOpened)
            .execute()
    }

    override fun find(
        tenant: TenantId,
        caseId: UUID,
    ): CaseRecord? =
        dsl
            .selectFrom(CASE_REGISTER)
            .where(CASE_REGISTER.TENANT_ID.eq(tenant.value), CASE_REGISTER.CASE_ID.eq(caseId))
            .fetchOne()
            ?.let {
                val kind = CaseKind.valueOf(checkNotNull(it.caseKind))
                CaseRecord(
                    caseId = checkNotNull(it.caseId),
                    kind = kind,
                    status = CaseStatus.valueOf(checkNotNull(it.status)),
                    subjectRef = it.subjectRef,
                    openedAt = checkNotNull(it.openedAt).toInstant(),
                    security =
                        if (kind == CaseKind.SECURITY) {
                            SecurityCaseFacts(
                                reviewReference = checkNotNull(it.reviewReference),
                                auditLogEntryIds = checkNotNull(it.auditLogEntryIds).toList().filterNotNull(),
                                outcome = checkNotNull(it.outcome),
                                breachAssessmentOpened = checkNotNull(it.breachAssessmentOpened),
                            )
                        } else {
                            null
                        },
                )
            }
}
