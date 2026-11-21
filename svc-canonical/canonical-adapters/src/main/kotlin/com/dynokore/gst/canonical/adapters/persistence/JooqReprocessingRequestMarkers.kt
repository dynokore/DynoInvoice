package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.REPROCESSING_REQUEST_MARKER
import org.jooq.DSLContext
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * TPL-11. jOOQ only. The marker of the reprocessing requests answered (INV-REQ-1003): one row per (tenant,
 * reprocessing id). The DSLContext is the one of the open `canonical` transaction bound to the tenant, so the marker
 * commits or rolls back with the pages written to the outbox.
 */
class JooqReprocessingRequestMarkers(
    private val dsl: DSLContext,
    private val clock: Clock,
) {
    /** True when the marker was inserted (the request is new for the tenant); false when it was answered before. */
    fun markAnswered(
        tenantId: UUID,
        reprocessingId: String,
    ): Boolean =
        dsl
            .insertInto(REPROCESSING_REQUEST_MARKER)
            .set(REPROCESSING_REQUEST_MARKER.TENANT_ID, tenantId)
            .set(REPROCESSING_REQUEST_MARKER.REPROCESSING_ID, reprocessingId)
            .set(REPROCESSING_REQUEST_MARKER.ANSWERED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() > 0
}
