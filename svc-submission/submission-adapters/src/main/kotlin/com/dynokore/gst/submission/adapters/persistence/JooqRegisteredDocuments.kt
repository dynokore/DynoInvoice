package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.application.arrival.RegisteredDocument
import com.dynokore.gst.submission.application.arrival.RegisteredDocuments
import com.dynokore.gst.submission.domain.metrics.RowKind
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext

/**
 * [RegisteredDocuments] over `submission.obligation_register_entry` (INV-T-21094): one select of entity, period and
 * kind where tenant and Doc UUID match. It opens no transaction and writes no row; the caller's handler owns the
 * transaction (ARC-21) and forced row level security applies as well (CS-19). A missing row is null, the normal case.
 * A kind that is not a [RowKind] name throws [IllegalStateException] (fail closed, never a default).
 */
class JooqRegisteredDocuments(
    private val dsl: DSLContext,
) : RegisteredDocuments {
    private val e = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY

    override fun find(
        tenant: TenantId,
        docUuid: String,
    ): RegisteredDocument? =
        dsl
            .select(e.ENTITY_REF, e.PERIOD_REF, e.KIND)
            .from(e)
            .where(e.TENANT_ID.eq(tenant.value))
            .and(e.DOC_UUID.eq(docUuid))
            .fetchOne()
            ?.let { row ->
                val kind = checkNotNull(row.get(e.KIND))
                RegisteredDocument(
                    entityRef = checkNotNull(row.get(e.ENTITY_REF)),
                    periodRef = checkNotNull(row.get(e.PERIOD_REF)),
                    kind = checkNotNull(RowKind.entries.firstOrNull { it.name == kind }) { "Unmapped kind" },
                )
            }
}
