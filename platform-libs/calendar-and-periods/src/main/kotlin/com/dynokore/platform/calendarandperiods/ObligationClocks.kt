package com.dynokore.platform.calendarandperiods

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

// Obligation clocks (INV-REQ-572, INV-REQ-1403, INV-Q-6097 A): pure functions that return facts; the embedding
// service decides what to do with them. Every input is an argument and the library reads no time source.

/** The due instant minus [margin]. A negative [margin] is refused. */
fun hardStop(
    due: ZonedDateTime,
    margin: Duration,
): Instant {
    require(!margin.isNegative) { "margin must not be negative: $margin" }
    return due.toInstant().minus(margin)
}

/** The earlier of [requested] and [hardStop]: the bound on a snooze or a pause. */
fun capAtHardStop(
    requested: Instant,
    hardStop: Instant,
): Instant = if (requested.isAfter(hardStop)) hardStop else requested

/**
 * The escalation times of a sent item. [t1] is never capped; [t2] is [requestedT2] capped at the hard stop.
 * Whether the re-send at [t1] still happens is the service's decision.
 */
data class EscalationSchedule(
    val t1: Instant,
    val requestedT2: Instant,
    val t2: Instant,
    val t2PulledForward: Boolean,
    val t1NotBeforeT2: Boolean,
    val hardStopPassed: Boolean,
)

fun escalationSchedule(
    sentAt: Instant,
    t1After: Duration,
    t2After: Duration,
    hardStop: Instant,
): EscalationSchedule {
    require(!t1After.isZero && !t1After.isNegative) { "t1After must be positive: $t1After" }
    require(t2After > t1After) { "t2After ($t2After) must be longer than t1After ($t1After)" }
    val t1 = sentAt.plus(t1After)
    val requestedT2 = sentAt.plus(t2After)
    val t2 = capAtHardStop(requestedT2, hardStop)
    return EscalationSchedule(
        t1 = t1,
        requestedT2 = requestedT2,
        t2 = t2,
        t2PulledForward = requestedT2.isAfter(hardStop),
        t1NotBeforeT2 = !t1.isBefore(t2),
        hardStopPassed = !hardStop.isAfter(sentAt),
    )
}

/**
 * The response deadline: [receivedOn] plus the period in calendar days, the period being [tenantDays] when present and
 * [packDays] otherwise. A tenant period may tighten the pack default but never lengthen it (INV-REQ-1401).
 */
fun responseDeadline(
    receivedOn: LocalDate,
    packDays: Int,
    tenantDays: Int?,
): LocalDate {
    require(packDays >= 1) { "packDays must be at least 1: $packDays" }
    if (tenantDays != null) {
        require(tenantDays >= 1) { "tenantDays must be at least 1: $tenantDays" }
        require(tenantDays <= packDays) { "tenantDays ($tenantDays) must not exceed packDays ($packDays)" }
    }
    return receivedOn.plusDays((tenantDays ?: packDays).toLong())
}

/** True when [today] is on or after [deadline] minus [leadDays], so an overdue request still prompts. */
fun noticeDue(
    deadline: LocalDate,
    today: LocalDate,
    leadDays: Int,
): Boolean {
    require(leadDays >= 0) { "leadDays must not be negative: $leadDays" }
    return !today.isBefore(deadline.minusDays(leadDays.toLong()))
}
