package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT
import com.dynokore.gst.canonical.application.document.DocumentHoldStore
import com.dynokore.gst.canonical.application.document.DocumentReleaseStore
import com.dynokore.gst.canonical.application.document.DocumentStates
import com.dynokore.gst.canonical.application.document.HoldableDocument
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.util.UUID

/**
 * TPL-11. jOOQ only, in the caller's transaction. The read is the hold store's read (one reading code). The release
 * writes only the granted columns `state` and `hold_reasons`, for a HELD document of the bound tenant (INV-REQ-479).
 */
class JooqDocumentReleaseStore(
    private val holds: DocumentHoldStore,
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : DocumentReleaseStore {
    override fun lockForRelease(docUuid: UUID): HoldableDocument? = holds.lockForHold(docUuid)

    override fun markReleased(docUuid: UUID) {
        val changed =
            dsl
                .update(DOCUMENT)
                .set(DOCUMENT.STATE, DocumentStates.INGESTED)
                .setNull(DOCUMENT.HOLD_REASONS)
                .where(DOCUMENT.TENANT_ID.eq(tenants.current().value))
                .and(DOCUMENT.DOC_UUID.eq(docUuid))
                .and(DOCUMENT.STATE.eq(DocumentStates.HELD))
                .execute()
        check(changed == 1) { "document $docUuid is not a HELD document of the bound tenant" }
    }
}
