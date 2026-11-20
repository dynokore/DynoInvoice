package com.dynokore.gst.canonical.adapters.persistence

import com.dynokore.gst.canonical.adapters.persistence.jooq.tables.references.SUBMISSION_ACKNOWLEDGEMENT
import org.jooq.DSLContext
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * TPL-11. jOOQ only. The acknowledgement replica (INV-T-25749): one row per (tenant, Doc UUID, version) fed by the
 * SubmissionAcknowledged event. The DSLContext is the one of the open `canonical` transaction bound to the tenant, so
 * the row commits or rolls back with the inbox row. The first acknowledgement stored wins.
 */
class JooqSubmissionAcknowledgements(
    private val dsl: DSLContext,
    private val clock: Clock,
) {
    /** True when the row was new and inserted; false when (tenant, doc, version) was stored before. */
    fun record(
        tenantId: UUID,
        docUuid: UUID,
        version: Int,
        acknowledgementId: String,
        acknowledgedAt: OffsetDateTime,
    ): Boolean =
        dsl
            .insertInto(SUBMISSION_ACKNOWLEDGEMENT)
            .set(SUBMISSION_ACKNOWLEDGEMENT.TENANT_ID, tenantId)
            .set(SUBMISSION_ACKNOWLEDGEMENT.DOC_UUID, docUuid)
            .set(SUBMISSION_ACKNOWLEDGEMENT.VERSION, version)
            .set(SUBMISSION_ACKNOWLEDGEMENT.ACKNOWLEDGEMENT_ID, acknowledgementId)
            .set(SUBMISSION_ACKNOWLEDGEMENT.ACKNOWLEDGED_AT, acknowledgedAt)
            .set(SUBMISSION_ACKNOWLEDGEMENT.RECORDED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .onConflict(
                SUBMISSION_ACKNOWLEDGEMENT.TENANT_ID,
                SUBMISSION_ACKNOWLEDGEMENT.DOC_UUID,
                SUBMISSION_ACKNOWLEDGEMENT.VERSION,
            ).doNothing()
            .execute() > 0
}
