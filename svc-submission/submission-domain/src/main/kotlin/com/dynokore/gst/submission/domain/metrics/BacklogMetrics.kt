package com.dynokore.gst.submission.domain.metrics

import com.dynokore.gst.submission.domain.ordering.LifecycleState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** One submission version as the backlog sees it; the due date is carried through unchanged. */
data class BacklogVersion(
    val docUuid: String,
    val version: Int,
    val entityRef: String,
    val periodRef: String,
    val state: LifecycleState,
    val queuedAt: Instant,
    val dueDate: LocalDate,
) {
    init {
        require(docUuid.isNotBlank()) { "docUuid must not be blank" }
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(periodRef.isNotBlank()) { "periodRef must not be blank" }
        require(version >= 1) { "version must be at least 1" }
    }
}

/** An unsent version with its own due date and whether that date is strictly before today. */
data class UnsentItem(
    val docUuid: String,
    val version: Int,
    val dueDate: LocalDate,
    val formallyLate: Boolean,
)

/** The four backlog values of one entity and prescribed period (INV-REQ-1370). */
data class BacklogValues(
    val entityRef: String,
    val periodRef: String,
    val queueDepth: Int,
    val oldestUnsentAge: Duration?,
    val failureRate: Ratio,
    val unacknowledgedCount: Int,
    val unsent: List<UnsentItem>,
)

/**
 * Pure backlog values per entity and period. There is no input for an outage, an extension or a
 * threshold: an outage changes no due date (INV-REQ-564), and thresholds belong to the alert policy.
 */
object BacklogMetrics {
    private val queued = setOf(LifecycleState.QUEUED, LifecycleState.RETRY)
    private val unsent = queued + LifecycleState.DEAD_LETTERED
    private val failed = setOf(LifecycleState.DEAD_LETTERED, LifecycleState.REJECTED)
    private val succeeded =
        setOf(LifecycleState.SENT, LifecycleState.SENT_UNCONFIRMED, LifecycleState.ACKNOWLEDGED)
    private val unacknowledged = setOf(LifecycleState.SENT_UNCONFIRMED)

    fun compute(
        versions: List<BacklogVersion>,
        now: Instant,
        today: LocalDate,
    ): List<BacklogValues> {
        val seen = HashSet<Pair<String, Int>>()
        versions.forEach { v ->
            require(seen.add(v.docUuid to v.version)) { "duplicate version ${v.docUuid}/${v.version}" }
        }
        return versions
            .groupBy { it.entityRef to it.periodRef }
            .toSortedMap(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            .map { (key, group) -> valuesOf(key.first, key.second, group, now, today) }
    }

    private fun valuesOf(
        entityRef: String,
        periodRef: String,
        group: List<BacklogVersion>,
        now: Instant,
        today: LocalDate,
    ): BacklogValues {
        val pending = group.filter { it.state in unsent }
        val failedCount = group.count { it.state in failed }
        val completed = failedCount + group.count { it.state in succeeded }
        val oldest = pending.minOfOrNull { it.queuedAt }
        return BacklogValues(
            entityRef = entityRef,
            periodRef = periodRef,
            queueDepth = group.count { it.state in queued },
            oldestUnsentAge = oldest?.let { Duration.between(it, now).coerceAtLeast(Duration.ZERO) },
            failureRate = Ratio(failedCount, completed),
            unacknowledgedCount = group.count { it.state in unacknowledged },
            unsent =
                pending
                    .sortedWith(compareBy<BacklogVersion> { it.dueDate }.thenBy { it.docUuid }.thenBy { it.version })
                    .map { UnsentItem(it.docUuid, it.version, it.dueDate, it.dueDate.isBefore(today)) },
        )
    }
}
