package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.application.queue.AcknowledgementRecorder
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.Instant
import java.time.ZoneOffset

/**
 * [AcknowledgementRecorder] over jOOQ (INV-REQ-797, INV-REQ-975). One UPDATE writes `acknowledgement_id` and
 * `acknowledged_at` together under the bound tenant, document and version, so a reader never sees one without the
 * other. The caller has locked the row and moved its state; one row is required, as in
 * [JooqSubmissionStore.recordAccepted]. It writes nothing else: the lock and the state move are the store's.
 */
class JooqAcknowledgementRecorder(
    private val dsl: DSLContext,
) : AcknowledgementRecorder {
    override fun record(
        tenant: TenantId,
        docUuid: String,
        version: Int,
        acknowledgementId: String,
        acknowledgedAt: Instant,
    ) {
        val s = Submission.SUBMISSION_
        val updated =
            dsl
                .update(s)
                .set(s.ACKNOWLEDGEMENT_ID, acknowledgementId)
                .set(s.ACKNOWLEDGED_AT, acknowledgedAt.atOffset(ZoneOffset.UTC))
                .where(s.TENANT_ID.eq(tenant.value))
                .and(s.DOC_UUID.eq(docUuid))
                .and(s.VERSION.eq(version))
                .execute()
        check(updated == 1) { "the locked submission row $docUuid version $version was not updated" }
    }
}
