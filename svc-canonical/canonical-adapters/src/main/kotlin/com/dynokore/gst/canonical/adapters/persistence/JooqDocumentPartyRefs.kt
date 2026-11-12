package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT_PARTY_REF
import com.dynokore.gst.canonical.application.document.DocumentPartyRefs
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

/** TPL-11. jOOQ only, in the open `canonical` transaction; the tenant is explicit, forced row-level security behind. */
class JooqDocumentPartyRefs(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : DocumentPartyRefs {
    override fun upsert(
        docUuid: UUID,
        partyRef: String,
    ) {
        dsl
            .insertInto(DOCUMENT_PARTY_REF)
            .set(DOCUMENT_PARTY_REF.TENANT_ID, tenants.current().value)
            .set(DOCUMENT_PARTY_REF.DOC_UUID, docUuid)
            .set(DOCUMENT_PARTY_REF.PARTY_REF, partyRef)
            .onConflict(DOCUMENT_PARTY_REF.TENANT_ID, DOCUMENT_PARTY_REF.DOC_UUID)
            .doUpdate()
            .set(DOCUMENT_PARTY_REF.PARTY_REF, DSL.excluded(DOCUMENT_PARTY_REF.PARTY_REF))
            .execute()
    }
}
