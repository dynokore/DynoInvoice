package com.dynokore.gst.controlPlane.domain.audit

import java.time.Instant
import java.time.Period
import java.time.ZoneOffset

/** The floor of an audit purge: the longest retention of any region, never under five years (INV-REQ-1378). */
sealed interface PurgeFloor {
    /** The floor [retention] counted back from the call; an entry at or before [limit] may be purged. */
    data class Determined(
        val retention: Period,
        val limit: Instant,
    ) : PurgeFloor

    /** A region has no figure, or there is no region: nothing may be purged. */
    data object Undeterminable : PurgeFloor
}

/**
 * Which audit entries an operator purge may delete (INV-Q-3028, INV-Q-3263, INV-Q-11446). Pure: the clock is the
 * caller's [Instant]; retentions are calendar periods counted back from it in UTC.
 */
object AuditPurgePolicy {
    val MINIMUM: Period = Period.ofYears(5)

    /** [figures] holds one entry per data region; a region that is unreachable or gives no figure is null. */
    fun floor(
        figures: List<Period?>,
        now: Instant,
    ): PurgeFloor {
        if (figures.isEmpty() || figures.any { it == null }) return PurgeFloor.Undeterminable
        val longest = (figures.filterNotNull() + MINIMUM).minBy { limitOf(it, now) }
        return PurgeFloor.Determined(longest, limitOf(longest, now))
    }

    /** A cut-off exactly at the floor is allowed; one instant younger is refused. */
    fun allows(
        cutOff: Instant,
        floor: PurgeFloor.Determined,
    ): Boolean = !cutOff.isAfter(floor.limit)

    private fun limitOf(
        retention: Period,
        now: Instant,
    ): Instant = now.atOffset(ZoneOffset.UTC).minus(retention).toInstant()
}
