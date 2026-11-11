package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.application.register.RegisterEntry
import com.dynokore.gst.submission.application.register.RegisterEntryStore
import com.dynokore.gst.submission.application.register.UpsertResult
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.Field

/**
 * [RegisterEntryStore] over the register entry (INV-REQ-975, INV-Q-16315 A): in one transaction of the bound tenant,
 * `insert ... on conflict (doc_uuid) do nothing` (one row: INSERTED), else a version-guarded `update` where tenant and
 * doc match and the stored `source_version` is null or lower (one row: UPDATED, none: UNCHANGED). No select, so the
 * result cannot race with a concurrent writer; forced row level security applies (CS-19). Values are never logged.
 */
class JooqRegisterEntryStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : RegisterEntryStore {
    private val e = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY

    override fun upsert(
        tenant: TenantId,
        entry: RegisterEntry,
    ): UpsertResult =
        runner.inTransaction {
            val values = valuesOf(entry)
            val inserted =
                dsl
                    .insertInto(e)
                    .set(e.TENANT_ID, tenant.value)
                    .set(e.DOC_UUID, entry.docUuid)
                    .set(values)
                    .onConflict(e.DOC_UUID)
                    .doNothing()
                    .execute()
            if (inserted == 1) {
                UpsertResult.INSERTED
            } else {
                val updated =
                    dsl
                        .update(e)
                        .set(values)
                        .where(e.TENANT_ID.eq(tenant.value))
                        .and(e.DOC_UUID.eq(entry.docUuid))
                        .and(e.SOURCE_VERSION.isNull.or(e.SOURCE_VERSION.lt(entry.sourceVersion)))
                        .execute()
                if (updated == 1) UpsertResult.UPDATED else UpsertResult.UNCHANGED
            }
        }

    /** Every column except the key (`tenant_id`, `doc_uuid`): set on insert and on a version-guarded update. */
    private fun valuesOf(entry: RegisterEntry): Map<Field<*>, Any?> {
        val f = entry.facts
        return mapOf(
            e.ENTITY_REF to entry.entityRef,
            e.PERIOD_REF to entry.periodRef,
            e.DUE_DATE to entry.dueDate,
            e.IN_SCOPE to entry.inScope,
            e.KIND to entry.kind.name,
            e.DOCUMENT_NUMBER to f.documentNumber,
            e.DOCUMENT_TYPE_CODE to f.documentTypeCode,
            e.DIRECTION to f.direction.name,
            e.COUNTERPARTY_NAME to f.counterpartyName,
            e.COUNTERPARTY_UEN to f.counterpartyUen,
            e.TAXABLE_AMOUNT to f.taxableAmount,
            e.GST_AMOUNT to f.gstAmount,
            e.CATEGORY_CODES to f.categoryCodes.sorted().toTypedArray(),
            e.SOURCE_VERSION to entry.sourceVersion,
        )
    }
}
