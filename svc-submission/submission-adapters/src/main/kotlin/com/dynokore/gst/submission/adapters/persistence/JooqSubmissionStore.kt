package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.SubmissionAttempt
import com.dynokore.gst.submission.application.queue.LockedSubmission
import com.dynokore.gst.submission.application.queue.NewAttempt
import com.dynokore.gst.submission.application.queue.NewReadySubmission
import com.dynokore.gst.submission.application.queue.SubmissionStore
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import java.time.ZoneOffset

/** [SubmissionStore] over the generated jOOQ classes of `submission` (CS-18), in the open tenant transaction. */
class JooqSubmissionStore(
    private val dsl: DSLContext,
) : SubmissionStore {
    override fun insertReady(
        tenant: TenantId,
        version: NewReadySubmission,
    ): Int =
        dsl
            .insertInto(Submission.SUBMISSION_)
            .set(Submission.SUBMISSION_.TENANT_ID, tenant.value)
            .set(Submission.SUBMISSION_.DOC_UUID, version.docUuid)
            .set(Submission.SUBMISSION_.VERSION, version.version)
            .set(Submission.SUBMISSION_.STATE, LifecycleState.READY.name)
            .set(Submission.SUBMISSION_.PAYLOAD_BYTES, version.payloadBytes)
            .set(Submission.SUBMISSION_.PAYLOAD_SHA256, version.payloadSha256)
            .set(Submission.SUBMISSION_.ENTITY_REF, version.entityRef)
            .set(Submission.SUBMISSION_.TRANSACTION_DATE, version.transactionDate)
            .onConflict(Submission.SUBMISSION_.DOC_UUID, Submission.SUBMISSION_.VERSION)
            .doNothing()
            .execute()

    override fun storedPayloadSha256(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): String? =
        dsl
            .select(Submission.SUBMISSION_.PAYLOAD_SHA256)
            .from(Submission.SUBMISSION_)
            .where(Submission.SUBMISSION_.TENANT_ID.eq(tenant.value))
            .and(Submission.SUBMISSION_.DOC_UUID.eq(docUuid))
            .and(Submission.SUBMISSION_.VERSION.eq(version))
            .fetchOne(Submission.SUBMISSION_.PAYLOAD_SHA256)

    override fun moveReadyToQueued(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): Int =
        dsl
            .update(Submission.SUBMISSION_)
            .set(Submission.SUBMISSION_.STATE, "QUEUED")
            .where(Submission.SUBMISSION_.TENANT_ID.eq(tenant.value))
            .and(Submission.SUBMISSION_.DOC_UUID.eq(docUuid))
            .and(Submission.SUBMISSION_.VERSION.eq(version))
            .and(Submission.SUBMISSION_.STATE.eq("READY"))
            .execute()

    override fun lockForUpdate(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): LockedSubmission? =
        dsl
            .selectFrom(Submission.SUBMISSION_)
            .where(Submission.SUBMISSION_.TENANT_ID.eq(tenant.value))
            .and(Submission.SUBMISSION_.DOC_UUID.eq(docUuid))
            .and(Submission.SUBMISSION_.VERSION.eq(version))
            .forUpdate()
            .fetchOne()
            ?.let {
                LockedSubmission(
                    state = LifecycleState.valueOf(checkNotNull(it.state)),
                    payloadBytes = it.payloadBytes,
                    payloadSha256 = it.payloadSha256,
                    attemptCount = checkNotNull(it.attemptCount),
                    entityRef = checkNotNull(it.entityRef),
                    transactionDate = it.transactionDate,
                )
            }

    override fun markSent(
        tenant: TenantId,
        docUuid: String,
        version: Int,
        attemptCount: Int,
    ) {
        val updated =
            dsl
                .update(Submission.SUBMISSION_)
                .set(Submission.SUBMISSION_.STATE, LifecycleState.SENT.name)
                .set(Submission.SUBMISSION_.ATTEMPT_COUNT, attemptCount)
                .where(Submission.SUBMISSION_.TENANT_ID.eq(tenant.value))
                .and(Submission.SUBMISSION_.DOC_UUID.eq(docUuid))
                .and(Submission.SUBMISSION_.VERSION.eq(version))
                .execute()
        check(updated == 1) { "the locked submission row $docUuid version $version was not updated" }
    }

    override fun insertAttempt(
        tenant: TenantId,
        attempt: NewAttempt,
    ) {
        dsl
            .insertInto(SubmissionAttempt.SUBMISSION_ATTEMPT)
            .set(SubmissionAttempt.SUBMISSION_ATTEMPT.TENANT_ID, tenant.value)
            .set(SubmissionAttempt.SUBMISSION_ATTEMPT.DOC_UUID, attempt.docUuid)
            .set(SubmissionAttempt.SUBMISSION_ATTEMPT.VERSION, attempt.version)
            .set(SubmissionAttempt.SUBMISSION_ATTEMPT.ATTEMPT_NUMBER, attempt.attemptNumber)
            .set(SubmissionAttempt.SUBMISSION_ATTEMPT.REQUEST, attempt.request)
            .set(SubmissionAttempt.SUBMISSION_ATTEMPT.CORRELATION_ID, attempt.correlationId)
            .set(SubmissionAttempt.SUBMISSION_ATTEMPT.REQUESTED_AT, attempt.requestedAt.atOffset(ZoneOffset.UTC))
            .execute()
    }
}
