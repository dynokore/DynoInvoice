package com.dynokore.gst.consoleBff.domain.session

import java.time.Duration
import java.time.Instant

/** The verdict of a session policy; exactly one per evaluation. */
enum class SessionVerdict { Valid, IdleExpired, AbsoluteExpired, ClockSkew }

/**
 * Idle and absolute timeouts of a console session (OWASP ASVS V3.3). Pure: the limits are arguments (configuration,
 * INV-Q-14429) and the instants come from the caller, so no clock is read here.
 */
class SessionPolicy(
    val idle: Duration,
    val absolute: Duration,
) {
    init {
        require(!idle.isZero && !idle.isNegative) { "idle must be positive" }
        require(!absolute.isZero && !absolute.isNegative) { "absolute must be positive" }
        require(idle <= absolute) { "idle must not be greater than absolute" }
    }

    fun evaluate(
        openedAt: Instant,
        lastSeenAt: Instant,
        now: Instant,
    ): SessionVerdict =
        when {
            now.isBefore(lastSeenAt) || now.isBefore(openedAt) -> SessionVerdict.ClockSkew
            Duration.between(openedAt, now) >= absolute -> SessionVerdict.AbsoluteExpired
            Duration.between(lastSeenAt, now) >= idle -> SessionVerdict.IdleExpired
            else -> SessionVerdict.Valid
        }
}
