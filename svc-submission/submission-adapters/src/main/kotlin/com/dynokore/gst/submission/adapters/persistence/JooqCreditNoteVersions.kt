package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.ObligationRegisterEntry
import com.dynokore.gst.submission.application.correction.CreditNoteVersions
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext

/** Reads the source version of the register entry of a credit note of the tenant (INV-REQ-558 behavior 6). */
class JooqCreditNoteVersions(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : CreditNoteVersions {
    private val e = ObligationRegisterEntry.OBLIGATION_REGISTER_ENTRY

    override fun registeredVersionOf(creditNoteDocUuid: String): Int? =
        dsl
            .select(e.SOURCE_VERSION)
            .from(e)
            .where(e.TENANT_ID.eq(tenants.current().value))
            .and(e.DOC_UUID.eq(creditNoteDocUuid))
            .fetchOne(e.SOURCE_VERSION)
}
