package com.dynokore.gst.submission.application.queue

import com.dynokore.gst.submission.domain.listing.SubmissionKind
import com.dynokore.gst.submission.domain.ordering.LifecycleState
import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The state rows of `submission` the queue slices write, behind a port so `application` stays free of jOOQ (ARC-03).
 * Every method runs in the caller's open tenant transaction (ARC-21); none opens or commits one.
 */
interface SubmissionStore {
    /**
     * Inserts the version in state `READY` with `ON CONFLICT (doc_uuid, version) DO NOTHING` and returns the number of
     * rows inserted: 0 when the version already exists (INV-REQ-816 behaviour 2, ruling INV-Q-7268 A).
     */
    fun insertReady(
        tenant: TenantId,
        version: NewReadySubmission,
    ): Int

    /** The stored `payload_sha256` of the version; null when the row is missing or holds no hash. */
    fun storedPayloadSha256(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): String?

    /**
     * Moves the version from `READY` to `QUEUED` with one UPDATE whose WHERE clause holds the state `READY`, and
     * returns the number of rows updated: 0 when the row is missing or in another state.
     *
     * @throws InFlightBreach when a 23505 violation names the constraint `submission_in_flight_uq`; any other is not.
     */
    fun moveReadyToQueued(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): Int

    /** Reads the version `FOR UPDATE`; null when the row does not exist (or belongs to another tenant). */
    fun lockForUpdate(
        tenant: TenantId,
        docUuid: String,
        version: Int,
    ): LockedSubmission?

    /** Sets the state `SENT` and the attempt count of the version whose row [lockForUpdate] returned. */
    fun markSent(
        tenant: TenantId,
        docUuid: String,
        version: Int,
        attemptCount: Int,
    )

    /**
     * Runs `UPDATE submission SET next_attempt_at = NULL WHERE state = 'RETRY' AND next_attempt_at IS NOT NULL AND
     * next_attempt_at <= now RETURNING doc_uuid, version` and returns the (Doc UUID, version) pairs.
     */
    fun clearDueRetries(
        tenant: TenantId,
        now: Instant,
    ): List<Pair<String, Int>>

    /** Writes `response` and `responded_at` on the attempt row of (docUuid, version, attemptNumber). */
    fun recordResponse(
        tenant: TenantId,
        response: AttemptResponse,
    )

    /** Writes the state of the locked row; `next_attempt_at` and the retry columns stay as they are. */
    fun setState(
        tenant: TenantId,
        docUuid: String,
        version: Int,
        state: LifecycleState,
    )

    /** State `RETRY`, `next_attempt_at`, `retry_failures` and `retry_first_failure_at` in one UPDATE. */
    fun scheduleRetry(
        tenant: TenantId,
        docUuid: String,
        version: Int,
        retry: RetrySchedule,
    )

    /** AP accepted: `ack_timer_started_at` set when null; the failure series reset (0 failures, no first failure). */
    fun recordAccepted(
        tenant: TenantId,
        docUuid: String,
        version: Int,
        at: Instant,
    )

    /** Inserts the attempt row: the request is the payload bytes, the response columns belong to the attempts slice. */
    fun insertAttempt(
        tenant: TenantId,
        attempt: NewAttempt,
    )
}

/**
 * The reads of the in-flight guard (INV-REQ-571), in the caller's open tenant transaction (ARC-21), apart from
 * [SubmissionStore] so that port stays small.
 */
interface InFlightStore {
    /** Reads every row of the Doc UUID `FOR UPDATE`, ordered by `version` (the lock order that serialises versions). */
    fun lockVersions(
        tenant: TenantId,
        docUuid: String,
    ): List<VersionState>

    /** Up to [limit] Doc UUIDs with a `READY` version and no version in one of the four in-flight states. */
    fun waitingDocUuids(
        tenant: TenantId,
        limit: Int,
    ): List<String>
}

/** One row of a Doc UUID as the in-flight guard reads it. */
class VersionState(
    val version: Int,
    val state: LifecycleState,
)

/** The `submission` row as the despatch step reads it. */
class LockedSubmission(
    val state: LifecycleState,
    val payloadBytes: ByteArray?,
    val payloadSha256: String?,
    val attemptCount: Int,
    val event: EventFacts,
    val outcome: OutcomeFacts,
) {
    val entityRef: String get() = event.entityRef
    val transactionDate: LocalDate? get() = event.transactionDate
    val acknowledgementId: String? get() = outcome.acknowledgementId
    val retryFailures: Int get() = outcome.retryFailures
    val retryFirstFailureAt: Instant? get() = outcome.retryFirstFailureAt
}

/** The entity and transaction date the `SubmissionDespatched` event takes from the row. */
class EventFacts(
    val entityRef: String,
    val transactionDate: LocalDate?,
)

/** The acknowledgement and the failure series the outcome step reads. */
class OutcomeFacts(
    val acknowledgementId: String?,
    val retryFailures: Int,
    val retryFirstFailureAt: Instant?,
)

/** The response to write on the attempt row of ([docUuid], [version], [attemptNumber]). */
class AttemptResponse(
    val docUuid: String,
    val version: Int,
    val attemptNumber: Int,
    val response: ByteArray,
    val respondedAt: Instant,
)

/** One `submission_attempt` row to insert. */
class NewAttempt(
    val docUuid: String,
    val version: Int,
    val attemptNumber: Int,
    val request: ByteArray?,
    val correlationId: UUID,
    val requestedAt: Instant,
)

/** The `submission` row a `PayloadReady` creates: state `READY`, the decoded bytes and their hash. */
@Suppress("LongParameterList")
class NewReadySubmission(
    val docUuid: String,
    val version: Int,
    val entityRef: String,
    val transactionDate: LocalDate?,
    val payloadBytes: ByteArray,
    val payloadSha256: String,
    val submissionType: SubmissionKind,
)

/** The values a `Retry` decision writes. */
class RetrySchedule(
    val nextAttemptAt: Instant,
    val failures: Int,
    val firstFailureAt: Instant,
)
