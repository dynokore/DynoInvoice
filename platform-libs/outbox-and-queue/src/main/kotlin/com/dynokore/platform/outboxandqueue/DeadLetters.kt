package com.dynokore.platform.outboxandqueue

import com.dynokore.platform.tenancykernel.TenantId
import java.time.Instant

enum class DeadLetterStatus {
    /** Waiting for triage. */
    OPEN,

    /** Queued for replay to one named consumer. */
    REPLAYED,

    /** Judged not to be replayed. */
    DISCARDED,
}

/**
 * A dead-lettered record as triage sees it. [raw] is content (CS-34): it is read in the letter's tenant session only,
 * so it is `null` for a letter without a tenant (INV-Q-2962, INV-Q-3130).
 */
data class DeadLetter(
    val id: Long,
    val consumer: String,
    val topic: String,
    val logOffset: Long,
    val tenant: TenantId?,
    val reason: String,
    val raw: String?,
    val status: DeadLetterStatus,
    val recordedAt: Instant,
    val resolvedAt: Instant?,
    val note: String?,
    val replayedTo: String?,
)

/**
 * Which dead letters. Every given field must match. [failureClass] is a prefix of the recorded reason,
 * [subject] the Doc UUID text (the CloudEvents `subject`) and [entity] the `entityref` of the raw envelope. The
 * last two read `raw`, so they match letters with a tenant only (read in that tenant's session).
 */
data class DeadLetterScope(
    val ids: Set<Long> = emptySet(),
    val tenant: TenantId? = null,
    val consumer: String? = null,
    val topic: String? = null,
    val failureClass: String? = null,
    val subject: String? = null,
    val entity: String? = null,
    val recordedFrom: Instant? = null,
    val recordedBefore: Instant? = null,
) {
    /** Whether at least one criterion is given. */
    val isNarrowed: Boolean
        get() = ids.isNotEmpty() || tenant != null || consumer != null || topic != null ||
            failureClass != null || subject != null || entity != null ||
            recordedFrom != null || recordedBefore != null
}

/** What a replay did. */
data class ReplayResult(
    val queued: Int,
    val skippedWithoutTenant: Int,
    val alreadyQueued: Int,
)

/**
 * Triage and replay of the dead letters of a service's schema. The worker role lists and resolves them from the
 * non-content columns only; `raw` is read in the letter's tenant session under the tenant policy, and a letter
 * without a tenant is triaged from metadata alone (RLS, `OutboxSchema`).
 */
interface DeadLetterTriage {
    /** The dead letters in [status] matching [scope], oldest first, at most [limit]. */
    fun list(
        scope: DeadLetterScope = DeadLetterScope(),
        status: DeadLetterStatus = DeadLetterStatus.OPEN,
        limit: Int = 100,
    ): List<DeadLetter>

    fun get(id: Long): DeadLetter?

    /** Marks an open dead letter as not to be replayed; returns whether it was open. */
    fun discard(
        id: Long,
        note: String,
    ): Boolean

    /**
     * Queues the open dead letters of [scope] on the replay lane for [consumer] alone: no other consumer receives
     * them. Idempotent: a dead letter is queued once. A dead letter without a tenant cannot be replayed (its `raw`
     * is unreadable).
     *
     * @throws IllegalArgumentException when [scope] gives no criterion.
     */
    fun replay(
        scope: DeadLetterScope,
        consumer: String,
    ): ReplayResult
}
