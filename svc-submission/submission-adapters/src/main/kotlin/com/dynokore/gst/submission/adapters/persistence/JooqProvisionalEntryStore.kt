package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ProvisionalRegisterEntry
import com.dynokore.gst.submission.application.register.Placement
import com.dynokore.gst.submission.application.register.ProvisionalEntry
import com.dynokore.gst.submission.application.register.ProvisionalEntryStore
import com.dynokore.platform.tenancykernel.TenantId
import com.dynokore.platform.tenancykernel.TransactionRunner
import org.jooq.DSLContext
import org.jooq.Field
import java.time.ZoneOffset

/**
 * [ProvisionalEntryStore] over `provisional_register_entry` (INV-REQ-570 behavior 2, INV-AC-1778). `place` is one
 * transaction of the bound tenant: a register row of the docUuid gives ALREADY_REGISTERED and writes nothing; else
 * `insert ... on conflict (tenant_id, doc_uuid) do update ... where stored docsequence < excluded docsequence` (one
 * row: PLACED, none: UNCHANGED). `release` clears the reason code of a row with a lower docsequence. Forced row level
 * security applies and the tenant is in every WHERE clause (CS-19). Values are never logged.
 */
class JooqProvisionalEntryStore(
    private val dsl: DSLContext,
    private val runner: TransactionRunner,
) : ProvisionalEntryStore {
    private val p = ProvisionalRegisterEntry.PROVISIONAL_REGISTER_ENTRY
    private val register = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY

    override fun place(
        tenant: TenantId,
        entry: ProvisionalEntry,
    ): Placement =
        runner.inTransaction {
            val registered =
                dsl.fetchExists(
                    dsl
                        .selectOne()
                        .from(register)
                        .where(register.TENANT_ID.eq(tenant.value))
                        .and(register.DOC_UUID.eq(entry.docUuid)),
                )
            if (registered) Placement.ALREADY_REGISTERED else upsert(tenant, entry)
        }

    private fun upsert(
        tenant: TenantId,
        entry: ProvisionalEntry,
    ): Placement {
        val heldAt = entry.heldAt.atOffset(ZoneOffset.UTC)
        val values =
            mapOf<Field<*>, Any?>(
                p.ENTITY_REF to entry.entityRef,
                p.PERIOD_REF to entry.periodRef,
                p.DIRECTION to entry.direction.name,
                p.DOCUMENT_DATE to entry.documentDate,
                p.TRANSACTION_DATE to entry.transactionDate,
                p.REASON_CODE to entry.reasonCode,
                p.DOCSEQUENCE to entry.docsequence,
                p.HELD_AT to heldAt,
            )
        val changed =
            dsl
                .insertInto(p)
                .set(p.TENANT_ID, tenant.value)
                .set(p.DOC_UUID, entry.docUuid)
                .set(values)
                .onConflict(p.TENANT_ID, p.DOC_UUID)
                .doUpdate()
                .set(values)
                .where(p.DOCSEQUENCE.lt(entry.docsequence))
                .execute()
        return if (changed == 1) Placement.PLACED else Placement.UNCHANGED
    }

    override fun release(
        tenant: TenantId,
        docUuid: String,
        docsequence: Long,
    ): Boolean =
        runner.inTransaction {
            dsl
                .update(p)
                .setNull(p.REASON_CODE)
                .set(p.DOCSEQUENCE, docsequence)
                .where(p.TENANT_ID.eq(tenant.value))
                .and(p.DOC_UUID.eq(docUuid))
                .and(p.DOCSEQUENCE.lt(docsequence))
                .execute() == 1
        }
}
