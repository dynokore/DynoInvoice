package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.DOCUMENT
import com.dynokore.gst.canonical.application.document.DocumentAggregateRefs
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.util.UUID

/** TPL-11. jOOQ only, in the open `canonical` transaction; the tenant is explicit, forced row-level security behind. */
class JooqDocumentAggregateRefs(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : DocumentAggregateRefs {
    /** Sets the aggregate reference of the tenant's document only where it is null; a second bind changes nothing. */
    override fun bind(
        docUuid: UUID,
        aggregateId: String,
    ): Boolean {
        require(aggregateId.isNotBlank()) { "aggregateId is blank" }
        return dsl
            .update(DOCUMENT)
            .set(DOCUMENT.AGGREGATE_ID, aggregateId)
            .where(DOCUMENT.TENANT_ID.eq(tenants.current().value))
            .and(DOCUMENT.DOC_UUID.eq(docUuid))
            .and(DOCUMENT.AGGREGATE_ID.isNull)
            .execute() == 1
    }

    override fun aggregateIdOf(docUuid: UUID): String? =
        dsl
            .select(DOCUMENT.AGGREGATE_ID)
            .from(DOCUMENT)
            .where(DOCUMENT.TENANT_ID.eq(tenants.current().value))
            .and(DOCUMENT.DOC_UUID.eq(docUuid))
            .fetchOne(DOCUMENT.AGGREGATE_ID)
}
