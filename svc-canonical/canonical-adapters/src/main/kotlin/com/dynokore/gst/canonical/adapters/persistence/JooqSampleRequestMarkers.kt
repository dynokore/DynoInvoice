package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.SAMPLE_REQUEST_MARKER
import org.jooq.DSLContext
import java.time.Clock
import java.util.UUID

/**
 * TPL-11. jOOQ only. The marker of the dry-run sample requests answered (INV-REQ-772, dry-run marker ruling): one row
 * per (tenant, dry run id). The DSLContext is the one of the open `canonical` transaction bound to the tenant, so the
 * marker commits or rolls back with the pages written to the outbox.
 */
class JooqSampleRequestMarkers(
    private val dsl: DSLContext,
    private val clock: Clock,
) {
    /** True when the marker was inserted (the request is new for the tenant); false when the id was answered before. */
    fun markAnswered(
        tenantId: UUID,
        dryRunId: String,
    ): Boolean =
        dsl
            .insertInto(SAMPLE_REQUEST_MARKER)
            .set(SAMPLE_REQUEST_MARKER.TENANT_ID, tenantId)
            .set(SAMPLE_REQUEST_MARKER.DRY_RUN_ID, dryRunId)
            .set(SAMPLE_REQUEST_MARKER.ANSWERED_AT, clock.instant().atOffset(java.time.ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() > 0
}
