package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.application.correction.CreditNoteTransitions
import com.dynokore.gst.submission.application.correction.NetRefusedMarks
import com.dynokore.gst.submission.application.correction.OriginalRef
import com.dynokore.gst.submission.application.correction.OriginalVersionSource
import com.dynokore.gst.submission.domain.correction.CorrectionReason
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext

/**
 * The register side of the credit note correction path (INV-REQ-558 behavior 6, INV-REQ-559): [OriginalVersionSource],
 * [CreditNoteTransitions] and [NetRefusedMarks] over the obligation register entry. Every call runs in the caller's
 * transaction and names the tenant of [tenants] besides the row level security. The original of a credit note is the
 * `preceding_doc_uuid` of its register entry; its current version is the `source_version` of the original's entry.
 */
class JooqCreditNoteCorrectionPorts(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : OriginalVersionSource,
    CreditNoteTransitions,
    NetRefusedMarks {
    private val e = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY

    override fun versionsOf(creditNoteDocUuid: String): List<LifecycleState> {
        val original = precedingOf(creditNoteDocUuid) ?: return emptyList()
        return JooqPrecedingStates(dsl).of(tenants.current(), original).orEmpty()
    }

    override fun originalOf(creditNoteDocUuid: String): OriginalRef? {
        val original = precedingOf(creditNoteDocUuid)
        val version =
            original?.let {
                dsl
                    .select(e.SOURCE_VERSION)
                    .from(e)
                    .where(e.TENANT_ID.eq(tenants.current().value))
                    .and(e.DOC_UUID.eq(it))
                    .fetchOne(e.SOURCE_VERSION)
            }
        return if (original == null || version == null) null else OriginalRef(original, version)
    }

    private fun precedingOf(creditNoteDocUuid: String): String? =
        dsl
            .select(e.PRECEDING_DOC_UUID)
            .from(e)
            .where(e.TENANT_ID.eq(tenants.current().value))
            .and(e.DOC_UUID.eq(creditNoteDocUuid))
            .fetchOne(e.PRECEDING_DOC_UUID)

    // The entry of a credit note that is submitted (separately or as posted) or waits is SUBMITTABLE already and the
    // queue is released by the arrival path, so these three leave the register unchanged.
    override fun submitSeparately(creditNoteDocUuid: String) = Unit

    override fun submitAsPosted(creditNoteDocUuid: String) = Unit

    override fun wait(creditNoteDocUuid: String) = Unit

    override fun notSubmittable(
        creditNoteDocUuid: String,
        reason: CorrectionReason,
    ) {
        dsl
            .update(e)
            .set(e.SCOPE_VERDICT, "NOT_SUBMITTABLE")
            .set(e.NOT_SUBMITTABLE_REASON, reason.code)
            .where(e.TENANT_ID.eq(tenants.current().value))
            .and(e.DOC_UUID.eq(creditNoteDocUuid))
            .execute()
    }

    override fun nettedBySystem(originalDocUuid: String): List<String> =
        dsl
            .select(e.DOC_UUID)
            .from(e)
            .where(e.TENANT_ID.eq(tenants.current().value))
            .and(e.PRECEDING_DOC_UUID.eq(originalDocUuid))
            .and(e.SCOPE_VERDICT.eq("NOT_SUBMITTABLE"))
            .and(e.NOT_SUBMITTABLE_REASON.eq(CorrectionReason.NETTED_BY_SYSTEM.code))
            .orderBy(e.DOC_UUID)
            .fetch(e.DOC_UUID)
            .filterNotNull()

    override fun lift(creditNoteDocUuid: String) {
        dsl
            .update(e)
            .set(e.SCOPE_VERDICT, "SUBMITTABLE")
            .setNull(e.NOT_SUBMITTABLE_REASON)
            .where(e.TENANT_ID.eq(tenants.current().value))
            .and(e.DOC_UUID.eq(creditNoteDocUuid))
            .and(e.NOT_SUBMITTABLE_REASON.eq(CorrectionReason.NETTED_BY_SYSTEM.code))
            .execute()
    }

    override fun store(creditNoteDocUuid: String) {
        dsl
            .update(e)
            .set(e.NET_REFUSED, true)
            .where(e.TENANT_ID.eq(tenants.current().value))
            .and(e.DOC_UUID.eq(creditNoteDocUuid))
            .execute()
    }

    override fun isRefused(creditNoteDocUuid: String): Boolean =
        dsl
            .select(e.NET_REFUSED)
            .from(e)
            .where(e.TENANT_ID.eq(tenants.current().value))
            .and(e.DOC_UUID.eq(creditNoteDocUuid))
            .fetchOne(e.NET_REFUSED) == true
}
