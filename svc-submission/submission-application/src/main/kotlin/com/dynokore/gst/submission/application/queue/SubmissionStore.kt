package com.dynokore.gst.submission.application.queue

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

    /** Inserts the attempt row: the request is the payload bytes, the response columns belong to the attempts slice. */
    fun insertAttempt(
        tenant: TenantId,
        attempt: NewAttempt,
    )
}

/** The `submission` row as the despatch step reads it. */
class LockedSubmission(
    val state: LifecycleState,
    val payloadBytes: ByteArray?,
    val payloadSha256: String?,
    val attemptCount: Int,
    val entityRef: String,
    val transactionDate: LocalDate?,
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
class NewReadySubmission(
    val docUuid: String,
    val version: Int,
    val entityRef: String,
    val transactionDate: LocalDate?,
    val payloadBytes: ByteArray,
    val payloadSha256: String,
)
