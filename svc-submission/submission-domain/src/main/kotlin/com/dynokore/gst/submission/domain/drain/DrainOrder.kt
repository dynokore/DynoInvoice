package com.dynokore.gst.submission.domain.drain

import java.time.Instant
import java.time.LocalDate

/** The three despatch tiers of a drain, in despatch order (INV-REQ-71 behavior 3). */
enum class Tier { WINDOW_OPEN, DRAINED_PERIOD, ROUTINE }

/** The entity and period being drained. */
data class DrainScope(
    val entityRef: String,
    val periodRef: String,
) {
    init {
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(periodRef.isNotBlank()) { "periodRef must not be blank" }
    }
}

/**
 * A queued document version.
 *
 * @property windowOpensOn the due date minus the safety margin, computed by the caller.
 */
data class DrainItem(
    val docUuid: String,
    val version: Int,
    val entityRef: String,
    val periodRef: String,
    val dueDate: LocalDate,
    val windowOpensOn: LocalDate,
    val queuedAt: Instant,
) {
    init {
        require(docUuid.isNotBlank()) { "docUuid must not be blank" }
        require(entityRef.isNotBlank()) { "entityRef must not be blank" }
        require(periodRef.isNotBlank()) { "periodRef must not be blank" }
        require(version >= 1) { "version must be at least 1" }
    }
}

data class RankedItem(
    val item: DrainItem,
    val tier: Tier,
)

/** Despatch order of a drain (INV-REQ-71 behavior 3, option A of INV-Q-6341). Pure: reads no clock, calendar or margin. */
object DrainOrder {
    private val ORDER: Comparator<RankedItem> =
        compareBy<RankedItem> { it.tier }
            .thenBy { it.item.dueDate }
            .thenBy { it.item.queuedAt }
            .thenBy { it.item.docUuid }
            .thenBy { it.item.version }

    fun rank(
        items: List<DrainItem>,
        drained: DrainScope?,
        today: LocalDate,
    ): List<RankedItem> {
        val keys = HashSet<Pair<String, Int>>()
        for (item in items) {
            require(keys.add(item.docUuid to item.version)) {
                "duplicate document version ${item.docUuid} v${item.version}"
            }
        }
        return items.map { RankedItem(it, tierOf(it, drained, today)) }.sortedWith(ORDER)
    }

    private fun tierOf(
        item: DrainItem,
        drained: DrainScope?,
        today: LocalDate,
    ): Tier =
        when {
            today >= item.windowOpensOn -> Tier.WINDOW_OPEN
            drained != null && item.entityRef == drained.entityRef && item.periodRef == drained.periodRef ->
                Tier.DRAINED_PERIOD
            else -> Tier.ROUTINE
        }
}
