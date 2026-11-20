package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.NettingDeclaration
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.application.correction.CreditNoteTransitions
import com.dynokore.gst.submission.application.correction.NetRefusedMarks
import com.dynokore.gst.submission.application.correction.NettingDeclarationRecorder
import com.dynokore.gst.submission.application.correction.NettingDeclarations
import com.dynokore.gst.submission.application.correction.OriginalRef
import com.dynokore.gst.submission.application.correction.OriginalVersionSource
import com.dynokore.gst.submission.domain.correction.CorrectionReason
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext

/** The credit notes of an original that a refused net derivation without a credit note list concerns. */
fun interface RefusedCreditNotes {
    /** The credit notes of [originalDocUuid] still awaiting a net: SUBMITTABLE, PURCHASE, not refused before. */
    fun awaiting(originalDocUuid: String): List<String>
}

/**
 * The register side of the credit note correction path (INV-REQ-558 behavior 6, INV-REQ-559): [OriginalVersionSource],
 * [NettingDeclarations], [NettingDeclarationRecorder], [CreditNoteTransitions] and [NetRefusedMarks] over the
 * obligation register entry and `submission.netting_declaration`. Every call runs in the caller's
 * transaction and names the tenant of [tenants] besides the row level security. The original of a credit note is the
 * `preceding_doc_uuid` of its register entry; its current version is the `source_version` of the original's entry.
 */
class JooqCreditNoteCorrectionPorts(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : OriginalVersionSource,
    NettingDeclarations,
    NettingDeclarationRecorder,
    CreditNoteTransitions,
    NetRefusedMarks,
    RefusedCreditNotes {
    private val e = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY
    private val d = NettingDeclaration.NETTING_DECLARATION

    override fun declared(creditNoteDocUuid: String): Boolean =
        dsl.fetchExists(
            d,
            d.TENANT_ID.eq(tenants.current().value).and(d.CREDIT_NOTE_DOC_UUID.eq(creditNoteDocUuid)),
        )

    override fun replaceDeclared(
        revisedDocUuid: String,
        creditNoteDocUuids: Set<String>,
    ) {
        val tenant = tenants.current().value
        val same = d.TENANT_ID.eq(tenant).and(d.REVISED_DOC_UUID.eq(revisedDocUuid))
        dsl.deleteFrom(d).where(same.and(d.CREDIT_NOTE_DOC_UUID.notIn(creditNoteDocUuids))).execute()
        creditNoteDocUuids.forEach {
            dsl
                .insertInto(d)
                .columns(d.TENANT_ID, d.REVISED_DOC_UUID, d.CREDIT_NOTE_DOC_UUID)
                .values(tenant, revisedDocUuid, it)
                .onConflictDoNothing()
                .execute()
        }
    }

    override fun awaiting(originalDocUuid: String): List<String> =
        dsl
            .select(e.DOC_UUID)
            .from(e)
            .where(e.TENANT_ID.eq(tenants.current().value))
            .and(e.PRECEDING_DOC_UUID.eq(originalDocUuid))
            .and(e.DIRECTION.eq("PURCHASE"))
            .and(e.SCOPE_VERDICT.eq("SUBMITTABLE"))
            .and(e.NOT_SUBMITTABLE_REASON.isNull)
            .and(e.NET_REFUSED.isFalse)
            .orderBy(e.DOC_UUID)
            .fetch(e.DOC_UUID)
            .filterNotNull()

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
