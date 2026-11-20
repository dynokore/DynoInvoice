package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.PROCESSED_NET_DERIVATION_REQUEST
import com.dynokore.gst.canonical.application.netting.ProcessedNetDerivationRequests
import com.dynokore.platform.tenancykernel.TenantContext
import org.jooq.DSLContext
import java.util.UUID

/**
 * TPL-11. jOOQ only, in the caller's open `canonical` transaction: the mark rolls back with the handling it guards.
 * The primary key is the CloudEvents id of the request, so a second mark of the same id inserts nothing.
 */
class JooqProcessedNetDerivationRequests(
    private val dsl: DSLContext,
    private val tenants: TenantContext,
) : ProcessedNetDerivationRequests {
    override fun markIfNew(requestEventId: UUID): Boolean =
        dsl
            .insertInto(PROCESSED_NET_DERIVATION_REQUEST)
            .set(PROCESSED_NET_DERIVATION_REQUEST.REQUEST_EVENT_ID, requestEventId)
            .set(PROCESSED_NET_DERIVATION_REQUEST.TENANT_ID, tenants.current().value)
            .onConflictDoNothing()
            .execute() == 1
}
