package com.dynokore.gst.submission.domain.readiness

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import java.time.LocalDate

/** One document of the period: version states in version order (the document-level state when no version exists). */
data class ReadinessEntry(
    val docUuid: String,
    val versions: List<LifecycleState>,
    val transactionDate: LocalDate,
    val overridden: Boolean,
    val reasonCode: String? = null,
) {
    init {
        require(docUuid.isNotBlank()) { "docUuid must not be blank" }
        require(versions.isNotEmpty()) { "versions must not be empty (entry $docUuid)" }
    }
}

enum class Bucket { ACKNOWLEDGED, QUEUED, BLOCKED_OR_ERROR, CLOSED }

data class Counts(
    val inScope: Int,
    val acknowledged: Int,
    val queued: Int,
    val blockedOrError: Int,
    val closed: Int,
    val unsubmitted: Int,
    val outstanding: Int,
) {
    init {
        check(
            inScope == acknowledged + queued + blockedOrError + closed,
        ) { "inScope must equal the sum of the four buckets" }
        check(unsubmitted == queued + blockedOrError) { "unsubmitted must equal queued + blockedOrError" }
        check(outstanding in 0..unsubmitted) { "outstanding must not exceed unsubmitted" }
    }
}

data class Residual(
    val docUuid: String,
    val bucket: Bucket,
    val state: LifecycleState,
    val reasonCode: String?,
    val overridden: Boolean,
)

/** The single readiness verdict (INV-REQ-945): ready, blocked with the outstanding count, or unable to determine. */
sealed interface ReadinessResult {
    data class Ready(
        val counts: Counts,
        val residual: List<Residual>,
    ) : ReadinessResult

    data class Blocked(
        val count: Int,
        val counts: Counts,
        val residual: List<Residual>,
    ) : ReadinessResult

    data object UnableToDetermine : ReadinessResult
}

/**
 * Counts one item per document by the latest state (INV-REQ-71, INV-REQ-339, INV-REQ-551, INV-REQ-735, INV-REQ-945,
 * INV-Q-6339 option A). An override removes an entry from `outstanding` only.
 */
object ReadinessCounter {
    fun evaluate(
        entries: List<ReadinessEntry>,
        obligationStartDate: LocalDate?,
        dataAvailable: Boolean,
    ): ReadinessResult {
        require(entries.map { it.docUuid }.toSet().size == entries.size) { "two entries share a docUuid" }
        if (!dataAvailable) return ReadinessResult.UnableToDetermine

        val counted =
            entries
                .filter { obligationStartDate == null || !it.transactionDate.isBefore(obligationStartDate) }
                .mapNotNull { entry -> bucketOf(entry.versions)?.let { entry to it } }

        fun number(bucket: Bucket) = counted.count { it.second == bucket }
        val queued = number(Bucket.QUEUED)
        val blockedOrError = number(Bucket.BLOCKED_OR_ERROR)
        val unsubmittedEntries = counted.filter { it.second == Bucket.QUEUED || it.second == Bucket.BLOCKED_OR_ERROR }
        val counts =
            Counts(
                inScope = counted.size,
                acknowledged = number(Bucket.ACKNOWLEDGED),
                queued = queued,
                blockedOrError = blockedOrError,
                closed = number(Bucket.CLOSED),
                unsubmitted = queued + blockedOrError,
                outstanding = unsubmittedEntries.count { !it.first.overridden },
            )
        val residual =
            unsubmittedEntries
                .map { (entry, bucket) ->
                    Residual(entry.docUuid, bucket, entry.versions.last(), entry.reasonCode, entry.overridden)
                }.sortedBy { it.docUuid }
        return if (counts.outstanding == 0) {
            ReadinessResult.Ready(counts, residual)
        } else {
            ReadinessResult.Blocked(counts.outstanding, counts, residual)
        }
    }

    /** A rejected latest version is acknowledged when an earlier version of the entry was acknowledged. */
    private fun rejectedBucket(versions: List<LifecycleState>): Bucket =
        if (LifecycleState.ACKNOWLEDGED in versions.dropLast(1)) Bucket.ACKNOWLEDGED else Bucket.BLOCKED_OR_ERROR

    /** The bucket of the latest state, or null when the entry is not counted. */
    private fun bucketOf(versions: List<LifecycleState>): Bucket? =
        when (versions.last()) {
            LifecycleState.NOT_SUBMITTABLE -> {
                null
            }

            LifecycleState.ACKNOWLEDGED,
            LifecycleState.SUPERSEDED,
            LifecycleState.EXTERNALLY_SUBMITTED,
            -> {
                Bucket.ACKNOWLEDGED
            }

            LifecycleState.CANCELLED,
            LifecycleState.WITHDRAWN,
            -> {
                Bucket.CLOSED
            }

            LifecycleState.REJECTED -> {
                rejectedBucket(versions)
            }

            LifecycleState.HELD,
            LifecycleState.BLOCKED,
            LifecycleState.DEAD_LETTERED,
            -> {
                Bucket.BLOCKED_OR_ERROR
            }

            LifecycleState.INGESTED,
            LifecycleState.READY,
            LifecycleState.AWAITING_PRECEDING,
            LifecycleState.QUEUED,
            LifecycleState.SENT,
            LifecycleState.SENT_UNCONFIRMED,
            LifecycleState.RETRY,
            LifecycleState.NETWORK_ISSUED,
            -> {
                Bucket.QUEUED
            }
        }
}
