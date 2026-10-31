package com.dynokore.gst.evidence.adapters.persistence

import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.CaseRegister.Companion.CASE_REGISTER
import com.dynokore.gst.evidence.adapters.persistence.jooq.tables.records.CaseRegisterRecord
import com.dynokore.gst.evidence.application.cases.PrivacyCaseStore
import com.dynokore.gst.evidence.domain.cases.CaseClosure
import com.dynokore.gst.evidence.domain.cases.CaseKind
import com.dynokore.gst.evidence.domain.cases.CaseOutcome
import com.dynokore.gst.evidence.domain.cases.CaseRecord
import com.dynokore.gst.evidence.domain.cases.CaseStatus
import com.dynokore.gst.evidence.domain.cases.PrivacyCaseFacts
import com.dynokore.gst.evidence.domain.cases.RequestKind
import com.dynokore.gst.evidence.domain.cases.SecurityCaseFacts
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** `case_register` over the service's tenant-bound [DSLContext] (jOOQ only, STK §3). */
class JooqCaseStore(
    private val dsl: DSLContext,
) : PrivacyCaseStore {
    override fun insert(
        tenant: TenantId,
        case: CaseRecord,
    ) {
        val security = case.security
        val privacy = case.privacy
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
            .set(CASE_REGISTER.REQUEST_KIND, privacy?.requestKind?.name)
            .set(CASE_REGISTER.REQUEST_REFERENCE, privacy?.requestReference)
            .set(CASE_REGISTER.RECEIVED_ON, privacy?.receivedOn)
            .set(CASE_REGISTER.RESPONSE_DEADLINE, privacy?.responseDeadline)
            .set(CASE_REGISTER.DEADLINE_PERIOD_DAYS, privacy?.deadlinePeriodDays)
            .set(CASE_REGISTER.CLOSED_AT, privacy?.closure?.closedAt?.atOffset(ZoneOffset.UTC))
            .set(CASE_REGISTER.CLOSURE_OUTCOME, privacy?.closure?.outcome?.name)
            .set(CASE_REGISTER.CLOSURE_BASIS, privacy?.closure?.basis)
            .set(CASE_REGISTER.NOTICE_PROMPTED_AT, privacy?.noticePromptedAt?.atOffset(ZoneOffset.UTC))
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
            ?.let(::toRecord)

    override fun findByRequestReference(
        tenant: TenantId,
        requestReference: String,
    ): CaseRecord? =
        dsl
            .selectFrom(CASE_REGISTER)
            .where(CASE_REGISTER.TENANT_ID.eq(tenant.value), CASE_REGISTER.REQUEST_REFERENCE.eq(requestReference))
            .fetchOne()
            ?.let(::toRecord)

    override fun update(
        tenant: TenantId,
        case: CaseRecord,
    ) {
        val closure = case.privacy?.closure
        val updated =
            dsl
                .update(CASE_REGISTER)
                .set(CASE_REGISTER.STATUS, case.status.name)
                .set(CASE_REGISTER.CLOSED_AT, closure?.closedAt?.atOffset(ZoneOffset.UTC))
                .set(CASE_REGISTER.CLOSURE_OUTCOME, closure?.outcome?.name)
                .set(CASE_REGISTER.CLOSURE_BASIS, closure?.basis)
                .where(CASE_REGISTER.TENANT_ID.eq(tenant.value), CASE_REGISTER.CASE_ID.eq(case.caseId))
                .execute()
        check(updated == 1) { "case ${case.caseId} does not exist" }
    }

    override fun markNoticePrompted(
        tenant: TenantId,
        caseId: UUID,
        at: Instant,
    ) {
        dsl
            .update(CASE_REGISTER)
            .set(CASE_REGISTER.NOTICE_PROMPTED_AT, at.atOffset(ZoneOffset.UTC))
            .where(
                CASE_REGISTER.TENANT_ID.eq(tenant.value),
                CASE_REGISTER.CASE_ID.eq(caseId),
                CASE_REGISTER.NOTICE_PROMPTED_AT.isNull,
            ).execute()
    }

    override fun findUnprompted(tenant: TenantId): List<CaseRecord> =
        dsl
            .selectFrom(CASE_REGISTER)
            .where(
                CASE_REGISTER.TENANT_ID.eq(tenant.value),
                CASE_REGISTER.CASE_KIND.eq(CaseKind.PRIVACY.name),
                CASE_REGISTER.STATUS.ne(CaseStatus.CLOSED.name),
                CASE_REGISTER.NOTICE_PROMPTED_AT.isNull,
            ).orderBy(CASE_REGISTER.OPENED_AT, CASE_REGISTER.CASE_ID)
            .fetch()
            .map(::toRecord)

    private fun toRecord(row: CaseRegisterRecord): CaseRecord {
        val kind = CaseKind.valueOf(checkNotNull(row.caseKind))
        return CaseRecord(
            caseId = checkNotNull(row.caseId),
            kind = kind,
            status = CaseStatus.valueOf(checkNotNull(row.status)),
            subjectRef = row.subjectRef,
            openedAt = checkNotNull(row.openedAt).toInstant(),
            security =
                if (kind == CaseKind.SECURITY) {
                    SecurityCaseFacts(
                        reviewReference = checkNotNull(row.reviewReference),
                        auditLogEntryIds = checkNotNull(row.auditLogEntryIds).toList().filterNotNull(),
                        outcome = checkNotNull(row.outcome),
                        breachAssessmentOpened = checkNotNull(row.breachAssessmentOpened),
                    )
                } else {
                    null
                },
            privacy = row.requestKind?.let { privacyOf(row, it) },
        )
    }

    private fun privacyOf(
        row: CaseRegisterRecord,
        requestKind: String,
    ): PrivacyCaseFacts =
        PrivacyCaseFacts(
            requestKind = RequestKind.valueOf(requestKind),
            requestReference = checkNotNull(row.requestReference),
            receivedOn = checkNotNull(row.receivedOn),
            responseDeadline = checkNotNull(row.responseDeadline),
            deadlinePeriodDays = checkNotNull(row.deadlinePeriodDays),
            closure =
                row.closedAt?.let {
                    CaseClosure(
                        closedAt = it.toInstant(),
                        outcome = CaseOutcome.valueOf(checkNotNull(row.closureOutcome)),
                        basis = checkNotNull(row.closureBasis),
                    )
                },
            noticePromptedAt = row.noticePromptedAt?.toInstant(),
        )
}
