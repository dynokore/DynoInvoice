package com.dynokore.gst.evidence.application.cases

import com.dynokore.gst.evidence.domain.cases.CaseRecord
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The privacy operations on the case store. All run in the caller's tenant transaction. It extends [CaseStore], whose
 * implementors keep compiling; a store that serves the privacy use cases implements this port.
 */
interface PrivacyCaseStore : CaseStore {
    fun findByRequestReference(
        tenant: TenantId,
        requestReference: String,
    ): CaseRecord?

    /** Replaces `status` and `privacy.closure` of an existing row and nothing else; throws when the row is missing. */
    fun update(
        tenant: TenantId,
        case: CaseRecord,
    )

    fun markNoticePrompted(
        tenant: TenantId,
        caseId: UUID,
        at: Instant,
    )

    /** The records of kind PRIVACY with status not CLOSED and `noticePromptedAt` null. */
    fun findUnprompted(tenant: TenantId): List<CaseRecord>
}

/** The response-period and notice-lead policy values (INV-REQ-1401), read through the config resolver by an adapter. */
interface PolicyValues {
    /** The pack default of the response period, in calendar days. */
    fun packResponseDays(): Int

    /** The tenant's own period on [on], or null when the tenant holds none. */
    fun tenantResponseDays(
        tenant: TenantId,
        on: LocalDate,
    ): Int?

    /** The notice lead time on [on], in calendar days before the deadline. */
    fun noticeLeadDays(
        tenant: TenantId,
        on: LocalDate,
    ): Int
}

/** Records the notice prompt of a case. Runs in the caller's tenant transaction. */
interface NoticeEvents {
    fun record(
        tenant: TenantId,
        case: CaseRecord,
        noticeLeadDays: Int,
    )
}
