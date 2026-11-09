package com.dynokore.gst.submission.adapters.persistence

import com.dynokore.gst.submission.adapters.persistence.jooq.tables.Submission
import com.dynokore.gst.submission.adapters.persistence.jooq.tables.SubmissionAttempt
import com.dynokore.gst.submission.application.queue.AttemptResponse
import com.dynokore.gst.submission.application.queue.EventFacts
import com.dynokore.gst.submission.application.queue.InFlightBreach
import com.dynokore.gst.submission.application.queue.LockedSubmission
import com.dynokore.gst.submission.application.queue.NewAttempt
import com.dynokore.gst.submission.application.queue.NewReadySubmission
import com.dynokore.gst.submission.application.queue.OutcomeFacts
import com.dynokore.gst.submission.application.queue.RetrySchedule
import com.dynokore.gst.submission.application.queue.SubmissionStore
import com.dynokore.gst.submission.domain.ordering.InFlight
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.postgresql.util.PSQLException
import java.time.Instant
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
            .set(Submission.SUBMISSION_.SUBMISSION_TYPE, version.submissionType.name)
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
        try {
            dsl
                .update(Submission.SUBMISSION_)
                .set(Submission.SUBMISSION_.STATE, "QUEUED")
                .where(Submission.SUBMISSION_.TENANT_ID.eq(tenant.value))
                .and(Submission.SUBMISSION_.DOC_UUID.eq(docUuid))
                .and(Submission.SUBMISSION_.VERSION.eq(version))
                .and(Submission.SUBMISSION_.STATE.eq("READY"))
                .execute()
        } catch (e: DataAccessException) {
            if (violatedConstraint(e) == InFlight.INDEX_NAME) throw InFlightBreach(docUuid, version, e)
            throw e
        }

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
                    event = EventFacts(checkNotNull(it.entityRef), it.transactionDate),
                    outcome =
                        OutcomeFacts(
                            it.acknowledgementId,
                            checkNotNull(it.retryFailures),
                            it.retryFirstFailureAt?.toInstant(),
                        ),
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

    override fun recordResponse(
        tenant: TenantId,
        response: AttemptResponse,
    ) {
        val a = SubmissionAttempt.SUBMISSION_ATTEMPT
        val updated =
            dsl
                .update(a)
                .set(a.RESPONSE, response.response)
                .set(a.RESPONDED_AT, response.respondedAt.atOffset(ZoneOffset.UTC))
                .where(a.TENANT_ID.eq(tenant.value))
                .and(a.DOC_UUID.eq(response.docUuid))
                .and(a.VERSION.eq(response.version))
                .and(a.ATTEMPT_NUMBER.eq(response.attemptNumber))
                .execute()
        check(updated == 1) {
            "no attempt row ${response.attemptNumber} of ${response.docUuid} version ${response.version}"
        }
    }

    override fun setState(
        tenant: TenantId,
        docUuid: String,
        version: Int,
        state: LifecycleState,
    ) {
        val s = Submission.SUBMISSION_
        val updated =
            dsl
                .update(s)
                .set(s.STATE, state.name)
                .where(s.TENANT_ID.eq(tenant.value))
                .and(s.DOC_UUID.eq(docUuid))
                .and(s.VERSION.eq(version))
                .execute()
        check(updated == 1) { "the locked submission row $docUuid version $version was not updated" }
    }

    override fun scheduleRetry(
        tenant: TenantId,
        docUuid: String,
        version: Int,
        retry: RetrySchedule,
    ) {
        val s = Submission.SUBMISSION_
        val updated =
            dsl
                .update(s)
                .set(s.STATE, LifecycleState.RETRY.name)
                .set(s.NEXT_ATTEMPT_AT, retry.nextAttemptAt.atOffset(ZoneOffset.UTC))
                .set(s.RETRY_FAILURES, retry.failures)
                .set(s.RETRY_FIRST_FAILURE_AT, retry.firstFailureAt.atOffset(ZoneOffset.UTC))
                .where(s.TENANT_ID.eq(tenant.value))
                .and(s.DOC_UUID.eq(docUuid))
                .and(s.VERSION.eq(version))
                .execute()
        check(updated == 1) { "the locked submission row $docUuid version $version was not updated" }
    }

    override fun recordAccepted(
        tenant: TenantId,
        docUuid: String,
        version: Int,
        at: Instant,
    ) {
        val s = Submission.SUBMISSION_
        val updated =
            dsl
                .update(s)
                .set(s.ACK_TIMER_STARTED_AT, DSL.coalesce(s.ACK_TIMER_STARTED_AT, at.atOffset(ZoneOffset.UTC)))
                .set(s.SUBMITTED_AT, DSL.coalesce(s.SUBMITTED_AT, at.atOffset(ZoneOffset.UTC)))
                .set(s.RETRY_FAILURES, 0)
                .setNull(s.RETRY_FIRST_FAILURE_AT)
                .where(s.TENANT_ID.eq(tenant.value))
                .and(s.DOC_UUID.eq(docUuid))
                .and(s.VERSION.eq(version))
                .execute()
        check(updated == 1) { "the locked submission row $docUuid version $version was not updated" }
    }

    override fun clearDueRetries(
        tenant: TenantId,
        now: Instant,
    ): List<Pair<String, Int>> =
        dsl
            .update(Submission.SUBMISSION_)
            .setNull(Submission.SUBMISSION_.NEXT_ATTEMPT_AT)
            .where(Submission.SUBMISSION_.TENANT_ID.eq(tenant.value))
            .and(Submission.SUBMISSION_.STATE.eq(LifecycleState.RETRY.name))
            .and(Submission.SUBMISSION_.NEXT_ATTEMPT_AT.isNotNull)
            .and(Submission.SUBMISSION_.NEXT_ATTEMPT_AT.le(now.atOffset(ZoneOffset.UTC)))
            .returning(Submission.SUBMISSION_.DOC_UUID, Submission.SUBMISSION_.VERSION)
            .fetch()
            .map { checkNotNull(it.docUuid) to checkNotNull(it.version) }

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

    private companion object {
        const val UNIQUE_VIOLATION = "23505"

        /** The constraint of a unique violation (23505), from the driver's structured field, not the message. */
        fun violatedConstraint(e: Throwable): String? =
            generateSequence(e) { it.cause }
                .filterIsInstance<PSQLException>()
                .firstOrNull { it.sqlState == UNIQUE_VIOLATION }
                ?.serverErrorMessage
                ?.constraint
    }
}
